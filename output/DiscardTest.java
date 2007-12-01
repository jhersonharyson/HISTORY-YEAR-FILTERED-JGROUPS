
package org.jgroups.tests;


import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.jgroups.*;
import org.jgroups.protocols.DISCARD;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.util.Promise;
import org.jgroups.util.Util;

import java.util.Properties;


/**
 * Tests the NAKACK (retransmission) and STABLE (garbage collection) protocols
 * by discarding 10% of all network-bound messages
 * @author Bela Ban
 * @version $Id: DiscardTest.java,v 1.14 2007/10/01 07:56:42 belaban Exp $
 */
public class DiscardTest extends TestCase {
    JChannel ch1, ch2;

    static final String udp_props="udp.xml"; // located in JGroups/conf, needs to be in the classpath
    static final long NUM_MSGS=10000;
    static final int  MSG_SIZE=1000;
    private static final String GROUP="DiscardTestGroup";
    final Promise ch1_all_received=new Promise();
    final Promise ch2_all_received=new Promise();


    public DiscardTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        super.setUp();
        ch1_all_received.reset();
        ch2_all_received.reset();
    }

    public void testDiscardProperties() throws Exception {
        _testLosslessReception(udp_props, true);
    }

    public void testFastProperties() throws Exception {
        _testLosslessReception(udp_props, false);
    }

    public void _testLosslessReception(String props, boolean discard) throws Exception {
        Address ch1_addr, ch2_addr;
        long start, stop;

        ch1=new JChannel(props);
        ch1.setReceiver(new MyReceiver(ch1_all_received, NUM_MSGS, "ch1"));
        ch2=new JChannel(props);
        ch2.setReceiver(new MyReceiver(ch2_all_received, NUM_MSGS, "ch2"));

        if(discard) {
            DISCARD discard_prot=new DISCARD();
            Properties properties=new Properties();
            properties.setProperty("down", "0.1");

            ch1.getProtocolStack().insertProtocol(discard_prot, ProtocolStack.BELOW, "PING");
            discard_prot=new DISCARD();
            properties=new Properties();
            properties.setProperty("down", "0.1");
            ch2.getProtocolStack().insertProtocol(discard_prot, ProtocolStack.BELOW, "PING");
        }


        ch1.connect(GROUP);
        ch1_addr=ch1.getLocalAddress();
        ch2.connect(GROUP);
        ch2_addr=ch2.getLocalAddress();

        Util.sleep(2000);
        View v=ch2.getView();
        System.out.println("**** ch2's view: " + v);
        assertEquals(2, v.size());
        assertTrue(v.getMembers().contains(ch1_addr));
        assertTrue(v.getMembers().contains(ch2_addr));

        System.out.println("sending " + NUM_MSGS + " 1K messages to all members (including myself)");
        start=System.currentTimeMillis();
        for(int i=0; i < NUM_MSGS; i++) {
            Message msg=createMessage(MSG_SIZE);
            ch1.send(msg);
            if(i % 1000 == 0)
                System.out.println("-- sent " + i + " messages");
        }

        System.out.println("-- waiting for ch1 and ch2 to receive " + NUM_MSGS + " messages");
        Long num_msgs;
        num_msgs=(Long)ch1_all_received.getResult();
        System.out.println("-- received " + num_msgs + " messages on ch1");

        num_msgs=(Long)ch2_all_received.getResult();
        stop=System.currentTimeMillis();
        System.out.println("-- received " + num_msgs + " messages on ch2");

        long diff=stop-start;
        double msgs_sec=NUM_MSGS / (diff / 1000.0);
        System.out.println("== Sent and received " + NUM_MSGS + " in " + diff + "ms, " +
                           msgs_sec + " msgs/sec");

        ch2.close();
        ch1.close();
    }


    static class MyReceiver extends ReceiverAdapter {
        final Promise p;
        final long num_msgs_expected;
        long num_msgs=0;
        String channel_name;
        boolean operational=true;

        public MyReceiver(final Promise p, final long num_msgs_expected, String channel_name) {
            this.p=p;
            this.num_msgs_expected=num_msgs_expected;
            this.channel_name=channel_name;
        }

        public void receive(Message msg) {
            if(!operational)
                return;
            num_msgs++;

            if(num_msgs > 0 && num_msgs % 1000 == 0)
                System.out.println("-- received " + num_msgs + " on " + channel_name);

            if(num_msgs >= num_msgs_expected) {
                System.out.println("SUCCESS: received all " + num_msgs_expected + " messages on " + channel_name);
                operational=false;
                p.setResult(new Long(num_msgs));
            }
        }

        public void viewAccepted(View new_view) {
            System.out.println("-- view (" + channel_name + "): " + new_view);
        }
    }


    private static Message createMessage(int size) {
        byte[] buf=new byte[size];
        for(int i=0; i < buf.length; i++) buf[i]=(byte)'x';
        return new Message(null, null, buf);
    }




    public static Test suite() {
        return new TestSuite(DiscardTest.class);
    }

    public static void main(String[] args) {
        junit.textui.TestRunner.run(suite());
    }




}


