//
// This file is part of the OpenNMS(R) Application.
//
// OpenNMS(R) is Copyright (C) 2004 The OpenNMS Group, Inc.  All rights reserved.
// OpenNMS(R) is a derivative work, containing both original code, included code and modified
// code that was published under the GNU General Public License. Copyrights for modified 
// and included code are below.
//
// OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
//
// Original code base Copyright (C) 1999-2001 Oculan Corp.  All rights reserved.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
//
// For more information contact:
// OpenNMS Licensing       <license@opennms.org>
//     http://www.opennms.org/
//     http://www.opennms.com/
//
package org.opennms.netmgt.poller;

import org.opennms.netmgt.xml.event.Event;

/**
 * Represents a network element in the Poller
 * @author brozow
 *
 */
abstract public class PollableElement {

    /**
     * last known/current status of the node
     */
    private PollStatus m_status;
    /**
     * Indicates if the service changed status as the result of most recent
     * poll.
     * 
     * Set by poll() method.
     */
    private boolean m_statusChanged;

    /**
     * @return Returns the status.
     */
    public PollStatus getStatus() {
        return m_status;
    }

    /**
     * @param status The status to set.
     */
    protected void setStatus(PollStatus status) {
        m_status = status;
    }

    public PollableElement(PollStatus status) {
        m_status = status;
        m_statusChanged = false;
    }
    
    abstract Poller getPoller();

    public void setStatusChanged() {
        setStatusChanged(true);
    }

    public void resetStatusChanged() {
        setStatusChanged(false);
    }

    public boolean statusChanged() {
        return m_statusChanged;
    }

    public int sendEvent(Event e) {
        getPoller().getEventManager().sendNow(e);
        return e.getDbid();
    }

    protected void setStatusChanged(boolean statusChangedFlag) {
        m_statusChanged = statusChangedFlag;
    }

}
