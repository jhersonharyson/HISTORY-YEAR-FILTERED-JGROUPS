// $Id: VERIFY_SUSPECT.java,v 1.10 2004/10/08 13:26:37 belaban Exp $

package org.jgroups.protocols;

import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.Header;
import org.jgroups.Message;
import org.jgroups.stack.Protocol;
import org.jgroups.util.Util;
import org.jgroups.util.Streamable;

import java.io.*;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Properties;
import java.util.Vector;


/**
 * Catches SUSPECT events traveling up the stack. Verifies that the suspected member is really dead. If yes,
 * passes SUSPECT event up the stack, otherwise discards it. Has to be placed somewhere above the FD layer and
 * below the GMS layer (receiver of the SUSPECT event). Note that SUSPECT events may be reordered by this protocol.
 */
public class VERIFY_SUSPECT extends Protocol implements Runnable {
    Address local_addr=null;
    long timeout=2000;   // number of millisecs to wait for an are-you-dead msg
    int num_msgs=1;     // number of are-you-alive msgs and i-am-not-dead responses (for redundancy)
    final Vector members=null;
    final Hashtable suspects=new Hashtable();  // keys=Addresses, vals=time in mcses since added
    Thread timer=null;


    public String getName() {
        return "VERIFY_SUSPECT";
    }


    public boolean setProperties(Properties props) {
        String str;

        super.setProperties(props);
        str=props.getProperty("timeout");
        if(str != null) {
            timeout=Long.parseLong(str);
            props.remove("timeout");
        }

        str=props.getProperty("num_msgs");
        if(str != null) {
            num_msgs=Integer.parseInt(str);
            if(num_msgs <= 0) {
                if(log.isWarnEnabled()) log.warn("num_msgs is invalid (" +
                        num_msgs + "): setting it to 1");
                num_msgs=1;
            }
            props.remove("num_msgs");
        }

        if(props.size() > 0) {
            System.err.println("VERIFY_SUSPECT.setProperties(): the following properties are not recognized:");
            props.list(System.out);
            return false;
        }
        return true;
    }


    public void up(Event evt) {
        Address suspected_mbr;
        Message msg, rsp;
        Object obj;
        VerifyHeader hdr;

        switch(evt.getType()) {

            case Event.SET_LOCAL_ADDRESS:
                local_addr=(Address)evt.getArg();
                break;

            case Event.SUSPECT:  // it all starts here ...
                suspected_mbr=(Address)evt.getArg();
                if(suspected_mbr == null) {
                    if(log.isErrorEnabled()) log.error("suspected member is null");
                    return;
                }
                suspect(suspected_mbr);
                return;  // don't pass up; we will decide later (after verification) whether to pass it up


            case Event.MSG:
                msg=(Message)evt.getArg();
                obj=msg.getHeader(getName());
                if(obj == null || !(obj instanceof VerifyHeader))
                    break;
                hdr=(VerifyHeader)msg.removeHeader(getName());
                switch(hdr.type) {
                    case VerifyHeader.ARE_YOU_DEAD:
                        if(hdr.from == null)
                            if(log.isErrorEnabled()) log.error("ARE_YOU_DEAD: hdr.from is null");
                        else {
                            for(int i=0; i < num_msgs; i++) {
                                rsp=new Message(hdr.from, null, null);
                                rsp.putHeader(getName(), new VerifyHeader(VerifyHeader.I_AM_NOT_DEAD, local_addr));
                                passDown(new Event(Event.MSG, rsp));
                            }
                        }
                        return;
                    case VerifyHeader.I_AM_NOT_DEAD:
                        if(hdr.from == null) {
                            if(log.isErrorEnabled()) log.error("I_AM_NOT_DEAD: hdr.from is null");
                            return;
                        }
                        unsuspect(hdr.from);
                        return;
                }
                return;
        }
        passUp(evt);
    }


    /**
     * Will be started when a suspect is added to the suspects hashtable. Continually iterates over the
     * entries and removes entries whose time have elapsed. For each removed entry, a SUSPECT event is passed
     * up the stack (because elapsed time means verification of member's liveness failed). Computes the shortest
     * time to wait (min of all timeouts) and waits(time) msecs. Will be woken up when entry is removed (in case
     * of successful verification of that member's liveness). Terminates when no entry remains in the hashtable.
     */
    public void run() {
        Address mbr;
        long val, curr_time, diff;

        while(timer != null && suspects.size() > 0) {
            diff=0;

            synchronized(suspects) {
                for(Enumeration e=suspects.keys(); e.hasMoreElements();) {
                    mbr=(Address)e.nextElement();
                    val=((Long)suspects.get(mbr)).longValue();
                    curr_time=System.currentTimeMillis();
                    diff=curr_time - val;
                    if(diff >= timeout) {  // haven't been unsuspected, pass up SUSPECT
                        if(log.isTraceEnabled()) log.trace("diff=" + diff + ", mbr " + mbr +
                                " is dead (passing up SUSPECT event)");
                        passUp(new Event(Event.SUSPECT, mbr));
                        suspects.remove(mbr);
                        continue;
                    }
                    diff=Math.max(diff, timeout - diff);
                }
            }

            if(diff > 0)
                Util.sleep(diff);
        }
        timer=null;
    }



    /* --------------------------------- Private Methods ----------------------------------- */


    /**
     * Sends ARE_YOU_DEAD message to suspected_mbr, wait for return or timeout
     */
    void suspect(Address mbr) {
        Message msg;
        if(mbr == null) return;

        synchronized(suspects) {
            if(suspects.containsKey(mbr))
                return;
            suspects.put(mbr, new Long(System.currentTimeMillis()));
            if(log.isTraceEnabled()) log.trace("verifying that " + mbr + " is dead");
            for(int i=0; i < num_msgs; i++) {
                msg=new Message(mbr, null, null);
                msg.putHeader(getName(), new VerifyHeader(VerifyHeader.ARE_YOU_DEAD, local_addr));
                passDown(new Event(Event.MSG, msg));
            }
        }
        if(timer == null)
            startTimer();
    }

    void unsuspect(Address mbr) {
        if(mbr == null) return;
        synchronized(suspects) {
            if(suspects.containsKey(mbr)) {
                if(log.isTraceEnabled()) log.trace("member " + mbr + " is not dead !");
                suspects.remove(mbr);
                passDown(new Event(Event.UNSUSPECT, mbr));
                passUp(new Event(Event.UNSUSPECT, mbr));
            }
        }
    }


    void startTimer() {
        if(timer == null) {
            timer=new Thread(this, "VERIFY_SUSPECT.TimerThread");
            timer.setDaemon(true);
            timer.start();
        }
    }

    public void stop() {
        Thread tmp;
        if(timer != null && timer.isAlive()) {
            tmp=timer;
            timer=null;
            tmp.interrupt();
            tmp=null;
        }
        timer=null;
    }
    /* ----------------------------- End of Private Methods -------------------------------- */





    public static class VerifyHeader extends Header implements Streamable {
        static final short ARE_YOU_DEAD=1;  // 'from' is sender of verify msg
        static final short I_AM_NOT_DEAD=2;  // 'from' is suspected member

        short type=ARE_YOU_DEAD;
        Address from=null;     // member who wants to verify that suspected_mbr is dead


        public VerifyHeader() {
        } // used for externalization

        VerifyHeader(short type) {
            this.type=type;
        }

        VerifyHeader(short type, Address from) {
            this(type);
            this.from=from;
        }


        public String toString() {
            switch(type) {
                case ARE_YOU_DEAD:
                    return "[VERIFY_SUSPECT: ARE_YOU_DEAD]";
                case I_AM_NOT_DEAD:
                    return "[VERIFY_SUSPECT: I_AM_NOT_DEAD]";
                default:
                    return "[VERIFY_SUSPECT: unknown type (" + type + ")]";
            }
        }

        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeShort(type);
            out.writeObject(from);
        }


        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            type=in.readShort();
            from=(Address)in.readObject();
        }

        public void writeTo(DataOutputStream out) throws IOException {
            out.writeShort(type);
            Util.writeAddress(from, out);
        }

        public void readFrom(DataInputStream in) throws IOException, IllegalAccessException, InstantiationException {
            type=in.readShort();
            from=Util.readAddress(in);
        }

    }


}

