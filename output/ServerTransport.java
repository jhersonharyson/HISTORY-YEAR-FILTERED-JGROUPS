package org.jgroups.tests.rt.transports;

import org.jgroups.Address;
import org.jgroups.blocks.cs.*;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.tests.rt.RtReceiver;
import org.jgroups.tests.rt.RtTransport;
import org.jgroups.util.Util;

import java.net.InetAddress;
import java.util.List;

/**
 * @author Bela Ban
 * @since  4.0
 */
public class ServerTransport extends ReceiverAdapter implements RtTransport {
    protected BaseServer   srv;
    protected RtReceiver   receiver;
    protected InetAddress  host;
    protected int          port=7800;
    protected boolean      server, nio;
    protected final Log    log=LogFactory.getLog(ServerTransport.class);


    public ServerTransport() {
    }

    public String[] options() {
        return new String[]{"-host <host>", "-port <port>", "-server", "-nio"};
    }

    public void options(String... options) throws Exception {
        if(options == null)
            return;
        for(int i=0; i < options.length; i++) {
            if(options[i].equals("-server")) {
                server=true;
                continue;
            }
            if(options[i].equals("-host")) {
                host=InetAddress.getByName(options[++i]);
                continue;
            }
            if(options[i].equals("-port")) {
                port=Integer.parseInt(options[++i]);
                continue;
            }
            if(options[i].equals("-nio")) {
                nio=true;
            }
        }
        if(host == null)
            host=InetAddress.getLocalHost();
    }


    public void receiver(RtReceiver receiver) {
        this.receiver=receiver;
    }

    public Object localAddress() {
        return null;
    }

    public List<Object> clusterMembers() {
        return null;
    }

    public void start(String ... options) throws Exception {
        options(options);
        if(server) {
            srv=nio? new NioServer(host, port) : new TcpServer(host, port);
            srv.connExpireTimeout(0);
            srv.tcpNodelay(false);
            srv.receiver(this);
            srv.start();
            System.out.printf("server started on %s (ctrl-c to terminate)\n", srv.localAddress());
        }
        else {
            srv=nio? new NioClient(null, 0, host, port) : new TcpClient(null, 0, host, port);
            srv.tcpNodelay(false);
            srv.receiver(this);
            srv.start();
        }
    }

    public void stop() {
        Util.close(this.srv);
    }

    public void send(Object dest, byte[] buf, int offset, int length) throws Exception {
        srv.send((Address)dest, buf, offset, length);
    }

    public void receive(Address sender, byte[] buf, int offset, int length) {
        if(receiver != null)
            receiver.receive(sender, buf, offset, length);
    }
}
