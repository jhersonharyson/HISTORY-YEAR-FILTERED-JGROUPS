// $Id: GmsImpl.java,v 1.6 2004/10/05 15:30:06 belaban Exp $

package org.jgroups.protocols.pbcast;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.Membership;
import org.jgroups.View;

import java.util.Vector;





public abstract class GmsImpl {
    protected GMS   gms=null;
    protected final Log   log=LogFactory.getLog(getClass());
    boolean         leaving=false;

    public abstract void      join(Address mbr);
    public abstract void      leave(Address mbr);

    public abstract void      handleJoinResponse(JoinRsp join_rsp);
    public abstract void      handleLeaveResponse();

    public abstract void      suspect(Address mbr);
    public abstract void      unsuspect(Address mbr);

    public void               merge(Vector other_coords)                           {;} // only processed by coord
    public void               handleMergeRequest(Address sender, Object merge_id)  {;} // only processed by coords
    public void               handleMergeResponse(MergeData data, Object merge_id) {;} // only processed by coords
    public void               handleMergeView(MergeData data, Object merge_id)     {;} // only processed by coords
    public void               handleMergeCancelled(Object merge_id)                {;} // only processed by coords

    public abstract JoinRsp   handleJoin(Address mbr);
    public abstract void      handleLeave(Address mbr, boolean suspected);
    public abstract void      handleViewChange(View new_view, Digest digest);
    public abstract void      handleSuspect(Address mbr);
    public          void      handleExit() {;}

    public boolean            handleUpEvent(Event evt) {return true;}
    public boolean            handleDownEvent(Event evt) {return true;}

    public void               init() throws Exception {leaving=false;}
    public void               start() throws Exception {leaving=false;}
    public void               stop() {leaving=true;}




    protected void wrongMethod(String method_name) {
        if(log.isErrorEnabled())
            log.error(method_name + "() should not be invoked on an instance of " + getClass().getName());
    }



    /**
       Returns potential coordinator based on lexicographic ordering of member addresses. Another
       approach would be to keep track of the primary partition and return the first member if we
       are the primary partition.
     */
    protected boolean iWouldBeCoordinator(Vector new_mbrs) {
        Membership tmp_mbrs=gms.members.copy();
        tmp_mbrs.merge(new_mbrs, null);
        tmp_mbrs.sort();
        if(tmp_mbrs.size() <= 0 || gms.local_addr == null)
            return false;
        return gms.local_addr.equals(tmp_mbrs.elementAt(0));
    }

}
