// $Id: FD_SOCK.java,v 1.30 2005/10/19 12:12:56 belaban Exp $

package org.jgroups.protocols;

import org.jgroups.*;
import org.jgroups.stack.IpAddress;
import org.jgroups.stack.Protocol;
import org.jgroups.util.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.List;


/**
 * Failure detection protocol based on sockets. Failure detection is ring-based. Each member creates a
 * server socket and announces its address together with the server socket's address in a multicast. A
 * pinger thread will be started when the membership goes above 1 and will be stopped when it drops below
 * 2. The pinger thread connects to its neighbor on the right and waits until the socket is closed. When
 * the socket is closed by the monitored peer in an abnormal fashion (IOException), the neighbor will be
 * suspected.<p> The main feature of this protocol is that no ping messages need to be exchanged between
 * any 2 peers, and failure detection relies entirely on TCP sockets. The advantage is that no activity
 * will take place between 2 peers as long as they are alive (i.e. have their server sockets open).
 * The disadvantage is that hung servers or crashed routers will not cause sockets to be closed, therefore
 * they won't be detected.
 * The FD_SOCK protocol will work for groups where members are on different hosts<p>
 * The costs involved are 2 additional threads: one that
 * monitors the client side of the socket connection (to monitor a peer) and another one that manages the
 * server socket. However, those threads will be idle as long as both peers are running.
 * @author Bela Ban May 29 2001
 */
public class FD_SOCK extends Protocol implements Runnable {
    long                get_cache_timeout=3000;            // msecs to wait for the socket cache from the coordinator
    final long          get_cache_retry_timeout=500;       // msecs to wait until we retry getting the cache from coord
    long                suspect_msg_interval=5000;         // (BroadcastTask): mcast SUSPECT every 5000 msecs
    int                 num_tries=3;                       // attempts coord is solicited for socket cache until we give up
    final Vector        members=new Vector(11);            // list of group members (updated on VIEW_CHANGE)
    boolean             srv_sock_sent=false;               // has own socket been broadcast yet ?
    final Vector        pingable_mbrs=new Vector(11);      // mbrs from which we select ping_dest. may be subset of 'members'
    final Promise       get_cache_promise=new Promise();   // used for rendezvous on GET_CACHE and GET_CACHE_RSP
    boolean             got_cache_from_coord=false;        // was cache already fetched ?
    Address             local_addr=null;                   // our own address
    ServerSocket        srv_sock=null;                     // server socket to which another member connects to monitor me
    InetAddress         srv_sock_bind_addr=null;           // the NIC on which the ServerSocket should listen
    ServerSocketHandler srv_sock_handler=null;             // accepts new connections on srv_sock
    IpAddress           srv_sock_addr=null;                // pair of server_socket:port
    Address             ping_dest=null;                    // address of the member we monitor
    Socket              ping_sock=null;                    // socket to the member we monitor
    InputStream         ping_input=null;                   // input stream of the socket to the member we monitor
    Thread              pinger_thread=null;                // listens on ping_sock, suspects member if socket is closed
    final Hashtable     cache=new Hashtable(11);           // keys=Addresses, vals=IpAddresses (socket:port)

    /** Start port for server socket (uses first available port starting at start_port). A value of 0 (default)
     * picks a random port */
    int                 start_port=0;
    final Promise       ping_addr_promise=new Promise();   // to fetch the ping_addr for ping_dest
    final Object        sock_mutex=new Object();           // for access to ping_sock, ping_input
    TimeScheduler       timer=null;
    final BroadcastTask bcast_task=new BroadcastTask();    // to transmit SUSPECT message (until view change)
    boolean             regular_sock_close=false;         // used by interruptPingerThread() when new ping_dest is computed
    int                 num_suspect_events=0;
    private static final int NORMAL_TEMINATION=9;
    private static final int ABNORMAL_TEMINATION=-1;
    private static final String name="FD_SOCK";

    BoundedList          suspect_history=new BoundedList(20);


    public String getName() {
        return name;
    }

    public String getLocalAddress() {return local_addr != null? local_addr.toString() : "null";}
    public String getMembers() {return members != null? members.toString() : "null";}
    public String getPingableMembers() {return pingable_mbrs != null? pingable_mbrs.toString() : "null";}
    public String getPingDest() {return ping_dest != null? ping_dest.toString() : "null";}
    public int getNumSuspectEventsGenerated() {return num_suspect_events;}
    public String printSuspectHistory() {
        StringBuffer sb=new StringBuffer();
        for(Enumeration en=suspect_history.elements(); en.hasMoreElements();) {
            sb.append(new Date()).append(": ").append(en.nextElement()).append("\n");
        }
        return sb.toString();
    }

    public boolean setProperties(Properties props) {
        String str, tmp=null;

        super.setProperties(props);
        str=props.getProperty("get_cache_timeout");
        if(str != null) {
            get_cache_timeout=Long.parseLong(str);
            props.remove("get_cache_timeout");
        }

        str=props.getProperty("suspect_msg_interval");
        if(str != null) {
            suspect_msg_interval=Long.parseLong(str);
            props.remove("suspect_msg_interval");
        }

        str=props.getProperty("num_tries");
        if(str != null) {
            num_tries=Integer.parseInt(str);
            props.remove("num_tries");
        }

        str=props.getProperty("start_port");
        if(str != null) {
            start_port=Integer.parseInt(str);
            props.remove("start_port");
        }


        // PropertyPermission not granted if running in an untrusted environment with JNLP.
        try {
            tmp=System.getProperty("bind.address");
            if(Util.isBindAddressPropertyIgnored()) {
                tmp=null;
            }
        }
        catch (SecurityException ex){
        }

        if(tmp != null)
            str=tmp;
        else
            str=props.getProperty("srv_sock_bind_addr");
        if(str != null) {
            try {
                srv_sock_bind_addr=InetAddress.getByName(str);
            }
            catch(UnknownHostException unknown) {
                if(log.isFatalEnabled()) log.fatal("(srv_sock_bind_addr): host " + str + " not known");
                return false;
            }
            props.remove("srv_sock_bind_addr");
        }


        if(props.size() > 0) {
            log.error("FD_SOCK.setProperties(): the following properties are not recognized: " + props);
            return false;
        }
        return true;
    }


    public void init() throws Exception {
        srv_sock_handler=new ServerSocketHandler();
        timer=stack != null ? stack.timer : null;
        if(timer == null)
            throw new Exception("FD_SOCK.init(): timer == null");
    }


    public void stop() {
        bcast_task.removeAll();
        stopPingerThread();
        stopServerSocket();
    }

    public void resetStats() {
        super.resetStats();
        num_suspect_events=0;
        suspect_history.removeAll();
    }


    public void up(Event evt) {
        Message msg;
        FdHeader hdr;

        switch(evt.getType()) {

        case Event.SET_LOCAL_ADDRESS:
            local_addr=(Address) evt.getArg();
            break;

        case Event.MSG:
            msg=(Message) evt.getArg();
            hdr=(FdHeader) msg.removeHeader(name);
            if(hdr == null)
                break;  // message did not originate from FD_SOCK layer, just pass up

            switch(hdr.type) {

            case FdHeader.SUSPECT:
                if(hdr.mbrs != null) {
                    if(log.isDebugEnabled()) log.debug("[SUSPECT] hdr=" + hdr);
                    for(int i=0; i < hdr.mbrs.size(); i++) {
                        passUp(new Event(Event.SUSPECT, hdr.mbrs.elementAt(i)));
                        passDown(new Event(Event.SUSPECT, hdr.mbrs.elementAt(i)));
                    }
                }
                else
                    if(warn) log.warn("[SUSPECT]: hdr.mbrs == null");
                break;

                // If I have the sock for 'hdr.mbr', return it. Otherwise look it up in my cache and return it
            case FdHeader.WHO_HAS_SOCK:
                if(local_addr != null && local_addr.equals(msg.getSrc()))
                    return; // don't reply to WHO_HAS bcasts sent by me !

                if(hdr.mbr == null) {
                    if(log.isErrorEnabled()) log.error("hdr.mbr is null");
                    return;
                }

                if(trace) log.trace("who-has-sock " + hdr.mbr);

                // 1. Try my own address, maybe it's me whose socket is wanted
                if(local_addr != null && local_addr.equals(hdr.mbr) && srv_sock_addr != null) {
                    sendIHaveSockMessage(msg.getSrc(), local_addr, srv_sock_addr);  // unicast message to msg.getSrc()
                    return;
                }

                // 2. If I don't have it, maybe it is in the cache
                if(cache.containsKey(hdr.mbr))
                    sendIHaveSockMessage(msg.getSrc(), hdr.mbr, (IpAddress) cache.get(hdr.mbr));  // ucast msg
                break;


                // Update the cache with the addr:sock_addr entry (if on the same host)
            case FdHeader.I_HAVE_SOCK:
                if(hdr.mbr == null || hdr.sock_addr == null) {
                    if(log.isErrorEnabled()) log.error("[I_HAVE_SOCK]: hdr.mbr is null or hdr.sock_addr == null");
                    return;
                }

                // if(!cache.containsKey(hdr.mbr))
                cache.put(hdr.mbr, hdr.sock_addr); // update the cache
                if(trace) log.trace("i-have-sock: " + hdr.mbr + " --> " +
                                                   hdr.sock_addr + " (cache is " + cache + ')');

                if(ping_dest != null && hdr.mbr.equals(ping_dest))
                    ping_addr_promise.setResult(hdr.sock_addr);
                break;

                // Return the cache to the sender of this message
            case FdHeader.GET_CACHE:
                if(hdr.mbr == null) {
                    if(log.isErrorEnabled()) log.error("(GET_CACHE): hdr.mbr == null");
                    return;
                }
                hdr=new FdHeader(FdHeader.GET_CACHE_RSP);
                hdr.cachedAddrs=(Hashtable) cache.clone();
                msg=new Message(hdr.mbr, null, null);
                msg.putHeader(name, hdr);
                passDown(new Event(Event.MSG, msg));
                break;

            case FdHeader.GET_CACHE_RSP:
                if(hdr.cachedAddrs == null) {
                    if(log.isErrorEnabled()) log.error("(GET_CACHE_RSP): cache is null");
                    return;
                }
                get_cache_promise.setResult(hdr.cachedAddrs);
                break;
            }
            return;
        }

        passUp(evt);                                        // pass up to the layer above us
    }


    public void down(Event evt) {
        Address mbr, tmp_ping_dest;
        View v;

        switch(evt.getType()) {

            case Event.UNSUSPECT:
                bcast_task.removeSuspectedMember((Address)evt.getArg());
                break;

            case Event.CONNECT:
                passDown(evt);
                srv_sock=Util.createServerSocket(srv_sock_bind_addr, start_port); // grab a random unused port above 10000
                srv_sock_addr=new IpAddress(srv_sock_bind_addr, srv_sock.getLocalPort());
                startServerSocket();
                //if(pinger_thread == null)
                  //  startPingerThread();
                break;

            case Event.VIEW_CHANGE:
                synchronized(this) {
                    v=(View) evt.getArg();
                    members.removeAllElements();
                    members.addAll(v.getMembers());
                    bcast_task.adjustSuspectedMembers(members);
                    pingable_mbrs.removeAllElements();
                    pingable_mbrs.addAll(members);
                    passDown(evt);

                    if(log.isDebugEnabled()) log.debug("VIEW_CHANGE received: " + members);

                    // 1. Get the addr:pid cache from the coordinator (only if not already fetched)
                    if(!got_cache_from_coord) {
                        getCacheFromCoordinator();
                        got_cache_from_coord=true;
                    }


                    // 2. Broadcast my own addr:sock to all members so they can update their cache
                    if(!srv_sock_sent) {
                        if(srv_sock_addr != null) {
                            sendIHaveSockMessage(null, // send to all members
                                    local_addr,
                                    srv_sock_addr);
                            srv_sock_sent=true;
                        }
                        else
                            if(warn) log.warn("(VIEW_CHANGE): srv_sock_addr == null");
                    }

                    // 3. Remove all entries in 'cache' which are not in the new membership
                    for(Enumeration e=cache.keys(); e.hasMoreElements();) {
                        mbr=(Address) e.nextElement();
                        if(!members.contains(mbr))
                            cache.remove(mbr);
                    }

                    if(members.size() > 1) {
                        if(pinger_thread != null && pinger_thread.isAlive()) {
                            tmp_ping_dest=determinePingDest();
                            if(ping_dest != null && tmp_ping_dest != null && !ping_dest.equals(tmp_ping_dest)) {
                                interruptPingerThread(); // allows the thread to use the new socket
                            }
                        }
                        else
                            startPingerThread(); // only starts if not yet running
                    }
                    else {
                        ping_dest=null;
                        stopPingerThread();
                    }
                }
                break;

            default:
                passDown(evt);
                break;
        }
    }


    /**
     * Runs as long as there are 2 members and more. Determines the member to be monitored and fetches its
     * server socket address (if n/a, sends a message to obtain it). The creates a client socket and listens on
     * it until the connection breaks. If it breaks, emits a SUSPECT message. It the connection is closed regularly,
     * nothing happens. In both cases, a new member to be monitored will be chosen and monitoring continues (unless
     * there are fewer than 2 members).
     */
    public void run() {
        Address tmp_ping_dest;
        IpAddress ping_addr;
        int max_fetch_tries=10;  // number of times a socket address is to be requested before giving up

        if(trace) log.trace("pinger_thread started"); // +++ remove

        while(pinger_thread != null) {
            tmp_ping_dest=determinePingDest(); // gets the neighbor to our right
            if(log.isDebugEnabled())
                log.debug("determinePingDest()=" + tmp_ping_dest + ", pingable_mbrs=" + pingable_mbrs);
            if(tmp_ping_dest == null) {
                ping_dest=null;
                pinger_thread=null;
                break;
            }
            ping_dest=tmp_ping_dest;
            ping_addr=fetchPingAddress(ping_dest);
            if(ping_addr == null) {
                if(log.isErrorEnabled()) log.error("socket address for " + ping_dest + " could not be fetched, retrying");
                if(--max_fetch_tries <= 0)
                    break;
                Util.sleep(2000);
                continue;
            }

            if(!setupPingSocket(ping_addr)) {
                // covers use cases #7 and #8 in GmsTests.txt
                if(log.isDebugEnabled()) log.debug("could not create socket to " + ping_dest + "; suspecting " + ping_dest);
                broadcastSuspectMessage(ping_dest);
                pingable_mbrs.removeElement(ping_dest);
                continue;
            }

            if(log.isDebugEnabled()) log.debug("ping_dest=" + ping_dest + ", ping_sock=" + ping_sock + ", cache=" + cache);

            // at this point ping_input must be non-null, otherwise setupPingSocket() would have thrown an exception
            try {
                if(ping_input != null) {
                    int c=ping_input.read();
                    switch(c) {
                        case NORMAL_TEMINATION:
                            if(log.isDebugEnabled())
                                log.debug("peer closed socket normally");
                            pinger_thread=null;
                            break;
                        case ABNORMAL_TEMINATION:
                            handleSocketClose(null);
                            break;
                        default:
                            break;
                    }
                }
            }
            catch(IOException ex) {  // we got here when the peer closed the socket --> suspect peer and then continue
                handleSocketClose(ex);
            }
            catch(Throwable catch_all_the_rest) {
                log.error("exception", catch_all_the_rest);
            }
        }
        if(log.isDebugEnabled()) log.debug("pinger thread terminated");
        pinger_thread=null;
    }




    /* ----------------------------------- Private Methods -------------------------------------- */


    void handleSocketClose(Exception ex) {
        teardownPingSocket();     // make sure we have no leftovers
        if(!regular_sock_close) { // only suspect if socket was not closed regularly (by interruptPingerThread())
            if(log.isDebugEnabled())
                log.debug("peer " + ping_dest + " closed socket (" + (ex != null ? ex.getClass().getName() : "eof") + ')');
            broadcastSuspectMessage(ping_dest);
            pingable_mbrs.removeElement(ping_dest);
        }
        else {
            if(log.isDebugEnabled()) log.debug("socket to " + ping_dest + " was reset");
            regular_sock_close=false;
        }
    }


    void startPingerThread() {
        if(pinger_thread == null) {
            pinger_thread=new Thread(this, "FD_SOCK Ping thread");
            pinger_thread.setDaemon(true);
            pinger_thread.start();
        }
    }


    void stopPingerThread() {
        if(pinger_thread != null && pinger_thread.isAlive()) {
            regular_sock_close=true;
            teardownPingSocket();
        }
        pinger_thread=null;
    }


    /**
     * Interrupts the pinger thread. The Thread.interrupt() method doesn't seem to work under Linux with JDK 1.3.1
     * (JDK 1.2.2 had no problems here), therefore we close the socket (setSoLinger has to be set !) if we are
     * running under Linux. This should be tested under Windows. (Solaris 8 and JDK 1.3.1 definitely works).<p>
     * Oct 29 2001 (bela): completely removed Thread.interrupt(), but used socket close on all OSs. This makes this
     * code portable and we don't have to check for OSs.
     * @see org.jgroups.tests.InterruptTest to determine whether Thread.interrupt() works for InputStream.read().
     */
    void interruptPingerThread() {
        if(pinger_thread != null && pinger_thread.isAlive()) {
            regular_sock_close=true;
            teardownPingSocket(); // will wake up the pinger thread. less elegant than Thread.interrupt(), but does the job
        }
    }

    void startServerSocket() {
        if(srv_sock_handler != null)
            srv_sock_handler.start(); // won't start if already running
    }

    void stopServerSocket() {
        if(srv_sock_handler != null)
            srv_sock_handler.stop();
    }


    /**
     * Creates a socket to <code>dest</code>, and assigns it to ping_sock. Also assigns ping_input
     */
    boolean setupPingSocket(IpAddress dest) {
        synchronized(sock_mutex) {
            if(dest == null) {
                if(log.isErrorEnabled()) log.error("destination address is null");
                return false;
            }
            try {
                ping_sock=new Socket(dest.getIpAddress(), dest.getPort());
                ping_sock.setSoLinger(true, 1);
                ping_input=ping_sock.getInputStream();
                return true;
            }
            catch(Throwable ex) {
                return false;
            }
        }
    }


    void teardownPingSocket() {
        synchronized(sock_mutex) {
            if(ping_sock != null) {
                try {
                    ping_sock.shutdownInput();
                    ping_sock.close();
                }
                catch(Exception ex) {
                }
                ping_sock=null;
            }
            if(ping_input != null) {
                try {
                    ping_input.close();
                }
                catch(Exception ex) {
                }
                ping_input=null;
            }
        }
    }


    /**
     * Determines coordinator C. If C is null and we are the first member, return. Else loop: send GET_CACHE message
     * to coordinator and wait for GET_CACHE_RSP response. Loop until valid response has been received.
     */
    void getCacheFromCoordinator() {
        Address coord;
        int attempts=num_tries;
        Message msg;
        FdHeader hdr;
        Hashtable result;

        get_cache_promise.reset();
        while(attempts > 0) {
            if((coord=determineCoordinator()) != null) {
                if(coord.equals(local_addr)) { // we are the first member --> empty cache
                    if(log.isDebugEnabled()) log.debug("first member; cache is empty");
                    return;
                }
                hdr=new FdHeader(FdHeader.GET_CACHE);
                hdr.mbr=local_addr;
                msg=new Message(coord, null, null);
                msg.putHeader(name, hdr);
                passDown(new Event(Event.MSG, msg));
                result=(Hashtable) get_cache_promise.getResult(get_cache_timeout);
                if(result != null) {
                    cache.putAll(result); // replace all entries (there should be none !) in cache with the new values
                    if(trace) log.trace("got cache from " + coord + ": cache is " + cache);
                    return;
                }
                else {
                    if(log.isErrorEnabled()) log.error("received null cache; retrying");
                }
            }

            Util.sleep(get_cache_retry_timeout);
            --attempts;
        }
    }


    /**
     * Sends a SUSPECT message to all group members. Only the coordinator (or the next member in line if the coord
     * itself is suspected) will react to this message by installing a new view. To overcome the unreliability
     * of the SUSPECT message (it may be lost because we are not above any retransmission layer), the following scheme
     * is used: after sending the SUSPECT message, it is also added to the broadcast task, which will periodically
     * re-send the SUSPECT until a view is received in which the suspected process is not a member anymore. The reason is
     * that - at one point - either the coordinator or another participant taking over for a crashed coordinator, will
     * react to the SUSPECT message and issue a new view, at which point the broadcast task stops.
     */
    void broadcastSuspectMessage(Address suspected_mbr) {
        Message suspect_msg;
        FdHeader hdr;

        if(suspected_mbr == null) return;

        if(trace) log.trace("suspecting " + suspected_mbr + " (own address is " + local_addr + ')');

        // 1. Send a SUSPECT message right away; the broadcast task will take some time to send it (sleeps first)
        hdr=new FdHeader(FdHeader.SUSPECT);
        hdr.mbrs=new Vector(1);
        hdr.mbrs.addElement(suspected_mbr);
        suspect_msg=new Message();
        suspect_msg.putHeader(name, hdr);
        passDown(new Event(Event.MSG, suspect_msg));

        // 2. Add to broadcast task and start latter (if not yet running). The task will end when
        //    suspected members are removed from the membership
        bcast_task.addSuspectedMember(suspected_mbr);
        if(stats) {
            num_suspect_events++;
            suspect_history.add(suspected_mbr);
        }
    }


    void broadcastWhoHasSockMessage(Address mbr) {
        Message msg;
        FdHeader hdr;

        if(local_addr != null && mbr != null)
            if(log.isDebugEnabled()) log.debug("[" + local_addr + "]: who-has " + mbr);

        msg=new Message();  // bcast msg
        hdr=new FdHeader(FdHeader.WHO_HAS_SOCK);
        hdr.mbr=mbr;
        msg.putHeader(name, hdr);
        passDown(new Event(Event.MSG, msg));
    }


    /**
     Sends or broadcasts a I_HAVE_SOCK response. If 'dst' is null, the reponse will be broadcast, otherwise
     it will be unicast back to the requester
     */
    void sendIHaveSockMessage(Address dst, Address mbr, IpAddress addr) {
        Message msg=new Message(dst, null, null);
        FdHeader hdr=new FdHeader(FdHeader.I_HAVE_SOCK);
        hdr.mbr=mbr;
        hdr.sock_addr=addr;
        msg.putHeader(name, hdr);

        if(trace) // +++ remove
            log.trace("hdr=" + hdr);

        passDown(new Event(Event.MSG, msg));
    }


    /**
     Attempts to obtain the ping_addr first from the cache, then by unicasting q request to <code>mbr</code>,
     then by multicasting a request to all members.
     */
    IpAddress fetchPingAddress(Address mbr) {
        IpAddress ret;
        Message ping_addr_req;
        FdHeader hdr;

        if(mbr == null) {
            if(log.isErrorEnabled()) log.error("mbr == null");
            return null;
        }
        // 1. Try to get from cache. Add a little delay so that joining mbrs can send their socket address before
        //    we ask them to do so
        ret=(IpAddress)cache.get(mbr);
        if(ret != null) {
            return ret;
        }

        Util.sleep(300);
        if((ret=(IpAddress)cache.get(mbr)) != null)
            return ret;


        // 2. Try to get from mbr
        ping_addr_promise.reset();
        ping_addr_req=new Message(mbr, null, null); // unicast
        hdr=new FdHeader(FdHeader.WHO_HAS_SOCK);
        hdr.mbr=mbr;
        ping_addr_req.putHeader(name, hdr);
        passDown(new Event(Event.MSG, ping_addr_req));
        ret=(IpAddress) ping_addr_promise.getResult(3000);
        if(ret != null) {
            return ret;
        }


        // 3. Try to get from all members
        ping_addr_req=new Message(null, null, null); // multicast
        hdr=new FdHeader(FdHeader.WHO_HAS_SOCK);
        hdr.mbr=mbr;
        ping_addr_req.putHeader(name, hdr);
        passDown(new Event(Event.MSG, ping_addr_req));
        ret=(IpAddress) ping_addr_promise.getResult(3000);
        return ret;
    }


    Address determinePingDest() {
        Address tmp;

        if(pingable_mbrs == null || pingable_mbrs.size() < 2 || local_addr == null)
            return null;
        for(int i=0; i < pingable_mbrs.size(); i++) {
            tmp=(Address) pingable_mbrs.elementAt(i);
            if(local_addr.equals(tmp)) {
                if(i + 1 >= pingable_mbrs.size())
                    return (Address) pingable_mbrs.elementAt(0);
                else
                    return (Address) pingable_mbrs.elementAt(i + 1);
            }
        }
        return null;
    }


    Address determineCoordinator() {
        return members.size() > 0 ? (Address) members.elementAt(0) : null;
    }





    /* ------------------------------- End of Private Methods ------------------------------------ */


    public static class FdHeader extends Header implements Streamable {
        public static final byte SUSPECT=10;
        public static final byte WHO_HAS_SOCK=11;
        public static final byte I_HAVE_SOCK=12;
        public static final byte GET_CACHE=13; // sent by joining member to coordinator
        public static final byte GET_CACHE_RSP=14; // sent by coordinator to joining member in response to GET_CACHE


        byte      type=SUSPECT;
        Address   mbr=null;           // set on WHO_HAS_SOCK (requested mbr), I_HAVE_SOCK
        IpAddress sock_addr;          // set on I_HAVE_SOCK

        // Hashtable<Address,IpAddress>
        Hashtable cachedAddrs=null;   // set on GET_CACHE_RSP
        Vector    mbrs=null;          // set on SUSPECT (list of suspected members)


        public FdHeader() {
        } // used for externalization

        public FdHeader(byte type) {
            this.type=type;
        }

        public FdHeader(byte type, Address mbr) {
            this.type=type;
            this.mbr=mbr;
        }

        public FdHeader(byte type, Vector mbrs) {
            this.type=type;
            this.mbrs=mbrs;
        }

        public FdHeader(byte type, Hashtable cachedAddrs) {
            this.type=type;
            this.cachedAddrs=cachedAddrs;
        }


        public String toString() {
            StringBuffer sb=new StringBuffer();
            sb.append(type2String(type));
            if(mbr != null)
                sb.append(", mbr=").append(mbr);
            if(sock_addr != null)
                sb.append(", sock_addr=").append(sock_addr);
            if(cachedAddrs != null)
                sb.append(", cache=").append(cachedAddrs);
            if(mbrs != null)
                sb.append(", mbrs=").append(mbrs);
            return sb.toString();
        }


        public static String type2String(byte type) {
            switch(type) {
                case SUSPECT:
                    return "SUSPECT";
                case WHO_HAS_SOCK:
                    return "WHO_HAS_SOCK";
                case I_HAVE_SOCK:
                    return "I_HAVE_SOCK";
                case GET_CACHE:
                    return "GET_CACHE";
                case GET_CACHE_RSP:
                    return "GET_CACHE_RSP";
                default:
                    return "unknown type (" + type + ')';
            }
        }

        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeByte(type);
            out.writeObject(mbr);
            out.writeObject(sock_addr);
            out.writeObject(cachedAddrs);
            out.writeObject(mbrs);
        }


        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            type=in.readByte();
            mbr=(Address) in.readObject();
            sock_addr=(IpAddress) in.readObject();
            cachedAddrs=(Hashtable) in.readObject();
            mbrs=(Vector) in.readObject();
        }

        public long size() {
            long retval=Global.BYTE_SIZE; // type
            retval+=Util.size(mbr);
            retval+=Util.size(sock_addr);

            retval+=Global.INT_SIZE; // cachedAddrs size
            Map.Entry entry;
            Address key;
            IpAddress val;
            if(cachedAddrs != null) {
                for(Iterator it=cachedAddrs.entrySet().iterator(); it.hasNext();) {
                    entry=(Map.Entry)it.next();
                    if((key=(Address)entry.getKey()) != null)
                        retval+=Util.size(key);
                    retval+=Global.BYTE_SIZE; // presence for val
                    if((val=(IpAddress)entry.getValue()) != null)
                        retval+=val.size();
                }
            }

            retval+=Global.INT_SIZE; // mbrs size
            if(mbrs != null) {
                for(int i=0; i < mbrs.size(); i++) {
                    retval+=Util.size((Address)mbrs.elementAt(i));
                }
            }

            return retval;
        }

        public void writeTo(DataOutputStream out) throws IOException {
            int size;
            out.writeByte(type);
            Util.writeAddress(mbr, out);
            Util.writeStreamable(sock_addr, out);
            size=cachedAddrs != null? cachedAddrs.size() : 0;
            out.writeInt(size);
            if(size > 0) {
                for(Iterator it=cachedAddrs.entrySet().iterator(); it.hasNext();) {
                    Map.Entry entry=(Map.Entry)it.next();
                    Address key=(Address)entry.getKey();
                    IpAddress val=(IpAddress)entry.getValue();
                    Util.writeAddress(key, out);
                    Util.writeStreamable(val, out);
                }
            }
            size=mbrs != null? mbrs.size() : 0;
            out.writeInt(size);
            if(size > 0) {
                for(Iterator it=mbrs.iterator(); it.hasNext();) {
                    Address address=(Address)it.next();
                    Util.writeAddress(address, out);
                }
            }
        }

        public void readFrom(DataInputStream in) throws IOException, IllegalAccessException, InstantiationException {
            int size;
            type=in.readByte();
            mbr=Util.readAddress(in);
            sock_addr=(IpAddress)Util.readStreamable(IpAddress.class, in);
            size=in.readInt();
            if(size > 0) {
                if(cachedAddrs == null)
                    cachedAddrs=new Hashtable();
                for(int i=0; i < size; i++) {
                    Address key=Util.readAddress(in);
                    IpAddress val=(IpAddress)Util.readStreamable(IpAddress.class, in);
                    cachedAddrs.put(key, val);
                }
            }
            size=in.readInt();
            if(size > 0) {
                if(mbrs == null)
                    mbrs=new Vector();
                for(int i=0; i < size; i++) {
                    Address addr=Util.readAddress(in);
                    mbrs.add(addr);
                }
            }
        }

    }


    /**
     * Handles the server-side of a client-server socket connection. Waits until a client connects, and then loops
     * until that client closes the connection. Note that there is no new thread spawned for the listening on the
     * client socket, therefore there can only be 1 client connection at the same time. Subsequent clients attempting
     * to create a connection will be blocked until the first client closes its connection. This should not be a problem
     * as the ring nature of the FD_SOCK protocol always has only 1 client connect to its right-hand-side neighbor.
     */
    private class ServerSocketHandler implements Runnable {
        Thread acceptor=null;
        /** List<ClientConnectionHandler> */
        final List clients=new ArrayList();



        ServerSocketHandler() {
            start();
        }

        void start() {
            if(acceptor == null) {
                acceptor=new Thread(this, "ServerSocket acceptor thread");
                acceptor.setDaemon(true);
                acceptor.start();
            }
        }


        void stop() {
            if(acceptor != null && acceptor.isAlive()) {
                try {
                    srv_sock.close(); // this will terminate thread, peer will receive SocketException (socket close)
                }
                catch(Exception ex) {
                }
            }
            synchronized(clients) {
                for(Iterator it=clients.iterator(); it.hasNext();) {
                    ClientConnectionHandler handler=(ClientConnectionHandler)it.next();
                    handler.stopThread();
                }
                clients.clear();
            }
            acceptor=null;
        }


        /** Only accepts 1 client connection at a time (saving threads) */
        public void run() {
            Socket client_sock;
            while(acceptor != null && srv_sock != null) {
                try {
                    if(trace) // +++ remove
                        log.trace("waiting for client connections on " + srv_sock.getInetAddress() + ":" +
                                  srv_sock.getLocalPort());
                    client_sock=srv_sock.accept();
                    if(trace) // +++ remove
                        log.trace("accepted connection from " + client_sock.getInetAddress() + ':' + client_sock.getPort());
                    ClientConnectionHandler client_conn_handler=new ClientConnectionHandler(client_sock, clients);
                    synchronized(clients) {
                        clients.add(client_conn_handler);
                    }
                    client_conn_handler.start();
                }
                catch(IOException io_ex2) {
                    break;
                }
            }
            acceptor=null;
        }
    }



    /** Handles a client connection; multiple client can connect at the same time */
    private static class ClientConnectionHandler extends Thread {
        Socket      client_sock=null;
        InputStream in;
        final Object mutex=new Object();
        List clients=new ArrayList();

        ClientConnectionHandler(Socket client_sock, List clients) {
            setName("ClientConnectionHandler");
            setDaemon(true);
            this.client_sock=client_sock;
            this.clients.addAll(clients);
        }

        void stopThread() {
            synchronized(mutex) {
                if(client_sock != null) {
                    try {
                        OutputStream out=client_sock.getOutputStream();
                        out.write(NORMAL_TEMINATION);
                    }
                    catch(Throwable t) {
                    }
                }
            }
            closeClientSocket();
        }

        void closeClientSocket() {
            synchronized(mutex) {
                if(client_sock != null) {
                    try {
                        client_sock.close();
                    }
                    catch(Exception ex) {
                    }
                    client_sock=null;
                }
            }
        }

        public void run() {
            try {
                synchronized(mutex) {
                    if(client_sock == null)
                        return;
                    in=client_sock.getInputStream();
                }
                while((in.read()) != -1) {
                }
            }
            catch(IOException io_ex1) {
            }
            finally {
                closeClientSocket();
                synchronized(clients) {
                    clients.remove(this);
                }
            }
        }
    }


    /**
     * Task that periodically broadcasts a list of suspected members to the group. Goal is not to lose
     * a SUSPECT message: since these are bcast unreliably, they might get dropped. The BroadcastTask makes
     * sure they are retransmitted until a view has been received which doesn't contain the suspected members
     * any longer. Then the task terminates.
     */
    private class BroadcastTask implements TimeScheduler.Task {
        final Vector suspected_mbrs=new Vector(7);
        boolean stopped=false;


        /** Adds a suspected member. Starts the task if not yet running */
        public void addSuspectedMember(Address mbr) {
            if(mbr == null) return;
            if(!members.contains(mbr)) return;
            synchronized(suspected_mbrs) {
                if(!suspected_mbrs.contains(mbr)) {
                    suspected_mbrs.addElement(mbr);
                    if(log.isDebugEnabled()) log.debug("mbr=" + mbr + " (size=" + suspected_mbrs.size() + ')');
                }
                if(stopped && suspected_mbrs.size() > 0) {
                    stopped=false;
                    timer.add(this, true);
                }
            }
        }


        public void removeSuspectedMember(Address suspected_mbr) {
            if(suspected_mbr == null) return;
            if(log.isDebugEnabled()) log.debug("member is " + suspected_mbr);
            synchronized(suspected_mbrs) {
                suspected_mbrs.removeElement(suspected_mbr);
                if(suspected_mbrs.size() == 0)
                    stopped=true;
            }
        }


        public void removeAll() {
            synchronized(suspected_mbrs) {
                suspected_mbrs.removeAllElements();
                stopped=true;
            }
        }


        /**
         * Removes all elements from suspected_mbrs that are <em>not</em> in the new membership
         */
        public void adjustSuspectedMembers(Vector new_mbrship) {
            Address suspected_mbr;

            if(new_mbrship == null || new_mbrship.size() == 0) return;
            synchronized(suspected_mbrs) {
                for(Iterator it=suspected_mbrs.iterator(); it.hasNext();) {
                    suspected_mbr=(Address) it.next();
                    if(!new_mbrship.contains(suspected_mbr)) {
                        it.remove();
                        if(log.isDebugEnabled())
                            log.debug("removed " + suspected_mbr + " (size=" + suspected_mbrs.size() + ')');
                    }
                }
                if(suspected_mbrs.size() == 0)
                    stopped=true;
            }
        }


        public boolean cancelled() {
            return stopped;
        }


        public long nextInterval() {
            return suspect_msg_interval;
        }


        public void run() {
            Message suspect_msg;
            FdHeader hdr;

            if(log.isDebugEnabled())
                log.debug("broadcasting SUSPECT message (suspected_mbrs=" + suspected_mbrs + ") to group");

            synchronized(suspected_mbrs) {
                if(suspected_mbrs.size() == 0) {
                    stopped=true;
                    if(log.isDebugEnabled()) log.debug("task done (no suspected members)");
                    return;
                }

                hdr=new FdHeader(FdHeader.SUSPECT);
                hdr.mbrs=(Vector) suspected_mbrs.clone();
            }
            suspect_msg=new Message();       // mcast SUSPECT to all members
            suspect_msg.putHeader(name, hdr);
            passDown(new Event(Event.MSG, suspect_msg));
            if(log.isDebugEnabled()) log.debug("task done");
        }
    }


}
