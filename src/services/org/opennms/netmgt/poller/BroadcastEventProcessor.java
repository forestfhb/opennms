//
// Copyright (C) 2002 Sortova Consulting Group, Inc.  All rights reserved.
// Parts Copyright (C) 1999-2001 Oculan Corp.  All rights reserved.
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
//      OpenNMS Licensing       <license@opennms.org>
//      http://www.opennms.org/
//      http://www.sortova.com/
//
//
// Tab Size = 8
//
package org.opennms.netmgt.poller;

import java.lang.*;

import java.net.InetAddress;
import java.net.UnknownHostException;

import java.util.Enumeration;
import java.util.Map;
import java.util.Iterator;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.log4j.Category;
import org.opennms.core.utils.ThreadCategory;

import org.opennms.netmgt.config.PollerConfigFactory;
import org.opennms.netmgt.config.DatabaseConnectionFactory;

import org.opennms.netmgt.EventConstants;
import org.opennms.netmgt.eventd.EventListener;
import org.opennms.netmgt.eventd.EventIpcManagerFactory;
import org.opennms.netmgt.scheduler.Scheduler;

// These generated by castor
//
import org.opennms.netmgt.xml.event.Event;
import org.opennms.netmgt.xml.event.Parms;
import org.opennms.netmgt.xml.event.Parm;
import org.opennms.netmgt.xml.event.Value;

// castor classes generated from the poller-configuration.xsd
import org.opennms.netmgt.config.poller.*;

/**
 *
 * @author <a href="mailto:weave@opennms.org">Brian Weaver</a>
 * @author <a href="http://www.opennms.org/">OpenNMS</a>
 */
final class BroadcastEventProcessor
	implements EventListener
{
	/**
	 * SQL statement used to delete oustanding SNMP service outages for the specified
	 * nodeid/interface in the event in the event of a primary snmp interface changed
	 * event.
	 */
	private static String SQL_DELETE_SNMP_OUTAGE = "DELETE FROM outages WHERE nodeid=? AND ipaddr=? AND ifregainedservice=null AND outages.serviceid=service.serviceid AND service.servicename='SNMP'";
	
	/**
	 * SQL statement used to query the 'ifServices' for a nodeid/ipaddr/service
	 * combination on the receipt of a 'nodeGainedService' to make sure there is
	 * atleast one row where the service status for the tuple is 'A'.
	 */
	private static String SQL_COUNT_IFSERVICE_STATUS = "select count(*) FROM ifServices, service WHERE nodeid=? AND ipaddr=? AND status='A' AND ifServices.serviceid=service.serviceid AND service.servicename=?";
	
	/**
	 * Integer constant for passing in to PollableNode.getNodeLock() method
	 * in order to indicate that the method should block until node lock is 
	 * available.
	 */
	private static int WAIT_FOREVER = 0;
	
	/**
	 * The map of service names to service models.
	 */
	private Map		m_monitors;

	/**
	 * The scheduler assocated with this reciever
	 */
	private Scheduler	m_scheduler;

	/**
	 * List of PollableService objects.
         */
	private	List		m_pollableServices;
	
	/**
	 * Create message selector to set to the subscription
	 */
	private void createMessageSelectorAndSubscribe()
	{
		// Create the selector for the ueis this service is interested in
		//
		List ueiList = new ArrayList();

		// nodeGainedService
		ueiList.add(EventConstants.NODE_GAINED_SERVICE_EVENT_UEI);

		// serviceDeleted
		// deleteService
		/* NOTE:  deleteService is only generated by the PollableService
		 * itself.  Therefore, we ignore it.  If future implementations
		 * allow other subsystems to generate this event, we may have
		 * to listen for it as well.
		 * 'serviceDeleted' is the response event that the outage manager
		 * generates.  We ignore this as well, since the PollableService
		 * has already taken action at the time it generated 'deleteService'
		 */
		//ueiList.add(EventConstants.SERVICE_DELETED_EVENT_UEI);
		//ueiList.add(EventConstants.DELETE_SERVICE_EVENT_UEI);

		// serviceManaged
		// serviceUnmanaged
		// interfaceManaged
		// interfaceUnmanaged
		/* NOTE:  These are all ignored because the responsibility
		 * is currently on the class generating the event to restart
		 * the poller service.  If that implementation is ever
		 * changed, this message selector should listen for these and 
		 * act on them.
		 */
		//ueiList.add(EventConstants.SERVICE_MANAGED_EVENT_UEI);
		//ueiList.add(EventConstants.SERVICE_UNMANAGED_EVENT_UEI);
		//ueiList.add(EventConstants.INTERFACE_MANAGED_EVENT_UEI);
		//ueiList.add(EventConstants.INTERFACE_UNMANAGED_EVENT_UEI);

		// interfaceIndexChanged
		// NOTE:  No longer interested in this event...if Capsd detects
		//        that in interface's index has changed a 
		//        'reinitializePrimarySnmpInterface' event is generated.
		//ueiList.add(EventConstants.INTERFACE_INDEX_CHANGED_EVENT_UEI);

		// interfaceReparented
		ueiList.add(EventConstants.INTERFACE_REPARENTED_EVENT_UEI);

		// reloadPollerConfig
		/* NOTE:  This is ignored because the reload is handled through
		 * an autoaction.
		 */
		//ueiList.add(EventConstants.RELOAD_POLLER_CONFIG_EVENT_UEI);


		// NODE OUTAGE RELATED EVENTS
		// 

		// nodeAdded
		/* NOTE:  This is ignored.  The real trigger will be the first
		 * nodeGainedService event, at which time the interface and
		 * node will be created
		 */
		//ueiList.add(EventConstants.NODE_ADDED_EVENT_UEI);
		
		// nodeDeleted
		ueiList.add(EventConstants.NODE_DELETED_EVENT_UEI);
		
		// duplicateNodeDeleted
		ueiList.add(EventConstants.DUP_NODE_DELETED_EVENT_UEI);
		
		// nodeGainedInterface
		/* NOTE:  This is ignored.  The real trigger will be the first
		 * nodeGainedService event, at which time the interface and
		 * node will be created
		 */
		//ueiList.add(EventConstants.NODE_GAINED_INTERFACE_EVENT_UEI);

		// interfaceDeleted
		ueiList.add(EventConstants.INTERFACE_DELETED_EVENT_UEI);
		
		// Subscribe to eventd
		EventIpcManagerFactory.init();
		EventIpcManagerFactory.getInstance().getManager().addEventListener(this, ueiList);
	}

	/**
	 * Process the event, construct a new PollableService object representing
 	 * the node/interface/service/pkg combination, and schedule the service
	 * for polling. 
	 * 
	 * If any errors occur scheduling the interface no error is returned.
	 *
	 * @param event	The event to process.
	 *
	 */
	private void nodeGainedServiceHandler(Event event)
	{
		Category log = ThreadCategory.getInstance(getClass());

		// First make sure the service gained is in active state before trying to
		// schedule
		java.sql.Connection dbConn = null;
		PreparedStatement stmt = null;
		try
		{
			dbConn = DatabaseConnectionFactory.getInstance().getConnection();
		
			stmt = dbConn.prepareStatement(SQL_COUNT_IFSERVICE_STATUS);
	
			stmt.setInt(1, (int)event.getNodeid());
			stmt.setString(2, event.getInterface());
			stmt.setString(3, event.getService());
	
			int count = -1;
			ResultSet rs = stmt.executeQuery();
			while(rs.next())
			{
				count = rs.getInt(1);
			}

			// count should be 1 to indicate an active status
			if (count <= 0)
			{
				if (log.isDebugEnabled())
				{
					log.debug("nodeGainedService: number check to see if service is in status: " + count); 
					log.debug("nodeGainedService: " + event.getNodeid() + "/" + event.getInterface() + "/" + event.getService() + " not active - hence not scheduled");
				}
				return;
			}

			if (log.isDebugEnabled())
				log.debug("nodeGainedService: " + event.getNodeid() + "/" + event.getInterface() + "/" + event.getService() + " active");
		}
		catch(SQLException sqlE)
		{
			log.error("SQLException during check to see if nodeid/ip/service is active", sqlE);
		}
		finally
		{
			// close the statement
			if (stmt != null)
				try { stmt.close(); } catch(SQLException sqlE) { };

			// close the connection
			if (dbConn != null)
				try { dbConn.close(); } catch(SQLException sqlE) { };					
		}
		
		PollerConfigFactory pCfgFactory = PollerConfigFactory.getInstance();
		PollerConfiguration config =  pCfgFactory.getConfiguration();
		Enumeration epkgs = config.enumeratePackage();
		while(epkgs.hasMoreElements())
		{
			org.opennms.netmgt.config.poller.Package pkg = (org.opennms.netmgt.config.poller.Package)epkgs.nextElement();
			
			// Make certain the the current service is in the package
			// and enabled!
			//
			if (!pCfgFactory.serviceInPackageAndEnabled(event.getService(), pkg))
			{
				if(log.isDebugEnabled())
					log.debug("nodeGainedService: interface " + event.getInterface() + 
							" gained service " + event.getService() + 
							", but the service is not enabled or does not exist in package: " 
							+ pkg.getName());
				continue;
			}
					
			// Is the interface in the package?
			//
			if(!pCfgFactory.interfaceInPackage(event.getInterface(), pkg))
			{
				if(log.isDebugEnabled())
					log.debug("nodeGainedService: interface " + event.getInterface() + 
							" gained service " + event.getService() + 
							", but the interface was not in package: " 
							+ pkg.getName());
				continue;
			}
			
			// Update Node Outage Hierarchy and schedule new service for polling
			//
			PollableNode pNode = null;
			PollableInterface pInterface = null;
			PollableService pSvc = null;
			boolean ownLock = false;
			boolean nodeCreated = false;
			boolean interfaceCreated = false;
			
			try
			{								
				// Does the node already exist in the poller's pollable node map?
				//
				int nodeId = (int)event.getNodeid();
				pNode = Poller.getInstance().getNode(nodeId);
				log.debug("nodeGainedService: attempting to retrieve pollable node object for nodeid " + nodeId);
				if (pNode == null)
				{
					// Nope...so we need to create it
					pNode = new PollableNode(nodeId);
					nodeCreated = true;
				} 
				else
				{
					// Obtain node lock
					//
					ownLock = pNode.getNodeLock(WAIT_FOREVER);
				}
				
				// Does the interface exist in the pollable node?
				//
				pInterface = pNode.getInterface(event.getInterface());
				if (pInterface == null)
				{
					// Create the PollableInterface and add it to the node
					if (log.isDebugEnabled())
						log.debug("nodeGainedService: creating new pollable interface: " + event.getInterface() + 
								" to pollable node " + pNode.getNodeId());
					pInterface = new PollableInterface(pNode, InetAddress.getByName(event.getInterface()));
					interfaceCreated = true;
				}
				
				// Create a new PollableService representing this node, interface,
				// service and package pairing
				log.debug("nodeGainedService: creating new pollable service object for: " + nodeId + "/" + event.getInterface() + "/" + event.getService());
				pSvc = new PollableService(pInterface,
								event.getService(),
								pkg,
								ServiceMonitor.SERVICE_AVAILABLE,
								new Date());

				// Initialize the service monitor with the pollable service and schedule 
				// the service for polling. 
				//							
				ServiceMonitor monitor = Poller.getInstance().getServiceMonitor(event.getService());
				monitor.initialize(pSvc);
				
				// Add new service to the pollable services list.  
				//
				m_pollableServices.add(pSvc);
				
				// Add the service to the PollableInterface object
				//
				// WARNING:  The PollableInterface stores services in a map
				//           keyed by service name, therefore, only the LAST
				//           PollableService aded to the interface for a 
				//           particular service will be represented in the
				//           map.  THIS IS BY DESIGN
				log.debug("nodeGainedService: adding pollable service to service list of interface: " + event.getInterface());
				pInterface.addService(pSvc);
				
				if (interfaceCreated)
				{
					// Add the interface to the node
					//
					// NOTE:  addInterface() calls recalculateStatus() automatically
					if (log.isDebugEnabled())
						log.debug("nodeGainedService: adding new pollable interface " + 
								event.getInterface() + " to pollable node " + pNode.getNodeId());
					pNode.addInterface(pInterface);
				}
				else
				{
					// Recalculate node status
					//
					pNode.recalculateStatus();
				}
				
				if (nodeCreated)
				{
					// Add the node to the node map
					//
					if (log.isDebugEnabled())
						log.debug("nodeGainedService: adding new pollable node: " + pNode.getNodeId());
					Poller.getInstance().addNode(pNode);
				}
								
				// Schedule the service for polling
				m_scheduler.schedule(pSvc, pSvc.recalculateInterval());
				if (log.isDebugEnabled())
					log.debug("nodeGainedService: " + event.getNodeid() + "/" + event.getInterface() + 
							"/" + event.getService() + " scheduled ");
			}
			catch(UnknownHostException ex)
			{
				log.error("Failed to schedule interface " + event.getInterface() + 
						" for service monitor " + event.getService() + ", illegal address", ex);
			}
			catch(InterruptedException ie)
			{
				log.error("Failed to schedule interface " + event.getInterface() + 
						" for service monitor " + event.getService() + ", thread interrupted", ie);
			}
			catch(RuntimeException rE)
			{
				log.warn("Unable to schedule " + event.getInterface() + " for service monitor " + event.getService() + 
						", reason: " + rE.getMessage());
			}
			catch(Throwable t)
			{
				log.error("Uncaught exception, failed to schedule interface " + event.getInterface() + 
						" for service monitor " + event.getService(), t);
			}
			finally
			{
				if (ownLock)
				{
					try
					{
						pNode.releaseNodeLock();
					}
					catch (InterruptedException iE)
					{
						log.error("Failed to release node lock on nodeid " + 
								pNode.getNodeId() + ", thread interrupted.");
					}
				}
			}
				
		} // end while more packages exist
	}
	
	/**
	 * This method is responsible for processing 'interfacReparented' events.  
	 * An 'interfaceReparented' event will have old and new nodeId parms
	 * associated with it. Node outage processing hierarchy will be updated to 
	 * reflect the new associations.
	 *
	 * @param event	The event to process.
	 *
	 */
	private void interfaceReparentedHandler(Event event)
	{
		Category log = ThreadCategory.getInstance(getClass());
		if (log.isDebugEnabled())
			log.debug("interfaceReparentedHandler:  processing interfaceReparented event for " + event.getInterface());
		
		// Verify that the event has an interface associated with it
		if (event.getInterface() == null)
			return;
			
		// Extract the old and new nodeId's from the event parms
		String oldNodeIdStr = null;
		String newNodeIdStr = null;
		Parms parms = event.getParms();
		if (parms != null)
		{
			String parmName = null;
			Value parmValue = null;
			String parmContent = null;
		
			Enumeration parmEnum = parms.enumerateParm();
			while(parmEnum.hasMoreElements())
			{
				Parm parm = (Parm)parmEnum.nextElement();
				parmName  = parm.getParmName();
				parmValue = parm.getValue();
				if (parmValue == null)
					continue;
				else 
					parmContent = parmValue.getContent();
	
				// old nodeid 
				if (parmName.equals(EventConstants.PARM_OLD_NODEID))
				{
					oldNodeIdStr = parmContent;
				}
						
				// new nodeid 
				else if (parmName.equals(EventConstants.PARM_NEW_NODEID))
				{
					newNodeIdStr = parmContent;
				}
			}
		}

		// Only proceed provided we have both an old and a new nodeId
		//
		if (oldNodeIdStr == null || newNodeIdStr == null)
		{
			log.error("interfaceReparentedHandler: old and new nodeId parms are required, unable to process.");
			return;
		}
		
		// Update node outage processing hierarchy based on this reparenting 
		// event.  Must "move" the interface from the "old" PollableNode object
		// to the "new" PollableNode object as identified by the old and new
		// nodeid parms.  
		// 	
		// In order to perform this "move" a node lock must be obtained on both
		// PollableNode objects.
		//
		
		// Retrieve old and new PollableNode objects from the Poller's pollable
		// node map.
		PollableNode oldPNode = null;
		PollableNode newPNode = null;
		try
		{
			oldPNode = Poller.getInstance().getNode(Integer.parseInt(oldNodeIdStr));
			newPNode = Poller.getInstance().getNode(Integer.parseInt(newNodeIdStr));
		}
		catch (NumberFormatException nfe)
		{
			log.error("interfaceReparentedHandler: failed converting old/new nodeid parm to integer, unable to process.");
			return;
		}
		
		// Sanity check, make certain we've were able to obtain both 
		// PollableNode objects.
		//
		if (oldPNode == null || newPNode == null)
		{
			log.error("interfaceReparentedHandler: old or new nodeId doesn't exist, unable to process.");
			return;
		}
		
		// Obtain node lock on both pollable node objects and then move the 
		// interface from the old node to the new node.
		//
                boolean ownOldLock = false;
                boolean ownNewLock = false;
		
                try
                {
			// Obtain lock on old nodeId...wait indefinitely
			log.debug("interfaceReparentedHandler: requesting node lock for old nodeId " + oldPNode.getNodeId());
			ownOldLock = oldPNode.getNodeLock(WAIT_FOREVER);
			PollableInterface pIf = oldPNode.getInterface(event.getInterface());
			log.debug("interfaceReparentedHandler: old node lock obtained, removing interface...");
			oldPNode.removeInterface(pIf);
			log.debug("interfaceReparentedHandler: recalculating old node status...");
			oldPNode.recalculateStatus();
			
			// Obtain lock on new nodeId...wait indefinitely
			log.debug("interfaceReparentedHandler: requesting node lock for new nodeId " + newPNode.getNodeId());
			ownNewLock = newPNode.getNodeLock(WAIT_FOREVER);
			log.debug("interfaceReparentedHandler: new node lock obtained, adding interface...");
			newPNode.addInterface(pIf);
			log.debug("interfaceReparentedHandler: recalculating new node status...");
			newPNode.recalculateStatus();
                }
                catch (InterruptedException iE)
                {
                        log.error("interfaceReparentedHandler: thread interrupted...failed to obtain required node locks");
                        return;
               	}
                finally
                {
			if (ownOldLock)
                        {
                                try
                                {
                                	oldPNode.releaseNodeLock();
                                }
                                catch (InterruptedException iE)
                                {
                                	log.error("interfaceReparentedHandler: thread interrupted...failed to release old node lock on nodeid " + 
							oldPNode.getNodeId());
                                }
                       	}

			if (ownNewLock)
                        {
                                try
                                {
                                	newPNode.releaseNodeLock();
                                }
                                catch (InterruptedException iE)
                                {
                                	log.error("interfaceReparentedHandler: thread interrupted...failed to release new node lock on nodeid " + 
							newPNode.getNodeId());
                                }
                       	}
		}	
	}
	
	/** 
	 * This method is responsible for removing the node specified
	 * in the nodeDeleted event from the Poller's pollable node map.
	 */
	private void nodeDeletedHandler(Event event)
	{
		Category log = ThreadCategory.getInstance(getClass());
		
		int nodeId = (int)event.getNodeid();
		
		PollableNode pNode = Poller.getInstance().getNode(nodeId);
		if (pNode == null)  // Sanity check
		{
			log.error("Nodeid " + nodeId + " does not exist in pollable node map, unable to delete node.");
			return;
		}
		
		// acquire lock to 'PollableNode'
		//
		boolean ownLock = false;
		try
		{
			// Attempt to obtain node lock...wait as long as it takes.
			// 
			if (log.isDebugEnabled())
				log.debug("nodeDeletedHandler: deleting nodeId: " + nodeId);
	
			ownLock = pNode.getNodeLock(WAIT_FOREVER);
			if (ownLock)
			{
				if (log.isDebugEnabled())
					log.debug("nodeDeletedHandler: obtained node lock for nodeid: " + nodeId);
			
				// Remove the node from the Poller's node map
				Poller.getInstance().removeNode(nodeId);
				
				// Iterate over the node's interfaces and delete
				// all services on each interface.
				Iterator iter = pNode.getInterfaces().iterator();
				while (iter.hasNext())
				{
					PollableInterface pIf = (PollableInterface)iter.next();
					
					// Iterate over the interface's services and mark
					// them for deletion.
					Iterator svc_iter = pIf.getServices().iterator();
					while (svc_iter.hasNext())
					{
						PollableService pSvc = (PollableService)svc_iter.next();
						pSvc.markAsDeleted();
						
						// Now remove the service from the pollable services list
						m_pollableServices.remove(pSvc);
					}
					
					// Delete all entries from the interface's internal service map
					pIf.deleteAllServices();
				}
			
				// Delete all entries from the node's internal interface map
				pNode.deleteAllInterfaces();
				
				// Mark the node as deleted to prevent any further node 
				// outage processing on this node
				pNode.markAsDeleted();
				
				if (log.isDebugEnabled())
					log.debug("nodeDeletedHandler: deletion of nodeid " + pNode.getNodeId() + " completed.");
			}
			else
			{
				// failed to acquire lock
				log.error("nodeDeletedHandler: failed to obtain lock on nodeId " + nodeId);
			}
		}
		catch (InterruptedException iE)
		{
			// failed to acquire lock
			log.error("nodeDeletedHandler: thread interrupted...failed to obtain lock on nodeId " + nodeId);
		}
		catch (Throwable t)
		{
			log.error("exception caught processing nodeDeleted event for " + nodeId, t);
		}
		finally
		{
			if (ownLock)
			{
				if (log.isDebugEnabled())
					log.debug("nodeDeletedHandler: releasing node lock for nodeid: " + nodeId);
				try
				{
					pNode.releaseNodeLock();
				}
				catch (InterruptedException iE)
				{
					log.error("nodeDeletedHandler: thread interrupted...failed to release lock on nodeId " + nodeId);
				}
			}
		}
	}
	
	/** 
	 * 
	 */
	private void interfaceDeletedHandler(Event event)
	{
		Category log = ThreadCategory.getInstance(getClass());
		
		int nodeId = (int)event.getNodeid();
		
		PollableNode pNode = Poller.getInstance().getNode(nodeId);
		if (pNode == null)  // Sanity check
		{
			log.error("Nodeid " + nodeId + " does not exist in pollable node map, unable to delete interface " + event.getInterface());
			return;
		}
		
		// acquire lock to 'PollableNode'
		//
		boolean ownLock = false;
		try
		{
			// Attempt to obtain node lock...wait as long as it takes.
			// 
			if (log.isDebugEnabled())
				log.debug("interfaceDeletedHandler: deleting nodeid/interface: " + nodeId + 
							"/" + event.getInterface());
	
			ownLock = pNode.getNodeLock(WAIT_FOREVER);
			if (ownLock)
			{
				if (log.isDebugEnabled())
					log.debug("interfaceDeletedHandler: obtained node lock for nodeid: " + nodeId);
				
				// Retrieve the PollableInterface object corresponding to 
				// the interface address specified in the event
				PollableInterface pIf = pNode.getInterface(event.getInterface());
				if (pIf == null)
				{
					if (log.isDebugEnabled())
						log.debug("interfaceDeletedHandler: interface " + event.getInterface() + 
								" not in interface map for " + nodeId);
					return;
				}
				
				// Iterate over the interface's services and mark
				// them for deletion.
				//
				// NOTE:  This is probably overkill because by the time
				//        the Outage Mgr generates the interfaceDeleted
				// 	  event all of the interface's underlying 
				// 	  services have already been deleted...but just
				//	  to be safe...
				Iterator svc_iter = pIf.getServices().iterator();
				while (svc_iter.hasNext())
				{
					PollableService pSvc = (PollableService)svc_iter.next();
					pSvc.markAsDeleted();
					
					// Now remove the service from the pollable services list
					m_pollableServices.remove(pSvc);
				}
				
				// Delete all entries from the interface's internal service map
				pIf.deleteAllServices();
				
				// Delete the interface from the node
				pNode.removeInterface(pIf);
				
				// Recalculate node status
				pNode.recalculateStatus();
				
				// Debug dump pollable node content
				//
				if (log.isDebugEnabled())
				{
					log.debug("Interface deletion completed, dumping node info for nodeid " + pNode.getNodeId() + ", status=" + Pollable.statusType[pNode.getStatus()] );
					Iterator k = pNode.getInterfaces().iterator();
					while(k.hasNext())
					{
						PollableInterface tmpIf = (PollableInterface)k.next();
						log.debug("		interface=" + tmpIf.getAddress().getHostAddress() + " status=" + Pollable.statusType[tmpIf.getStatus()]);
						
						Iterator s = tmpIf.getServices().iterator();
						while(s.hasNext())
						{
							PollableService tmpSvc = (PollableService)s.next();
							log.debug("			service=" + tmpSvc.getServiceName() + " status=" + Pollable.statusType[tmpSvc.getStatus()]);
						}
					}
				}
			}
			else
			{
				// failed to acquire lock
				log.error("interfaceDeletedHandler: failed to obtain lock on nodeId " + nodeId);
			}
		}
		catch (InterruptedException iE)
		{
			// failed to acquire lock,
			log.error("interfaceDeletedHandler: thread interrupted...failed to obtain lock on nodeId " + nodeId);
		}
		catch (Throwable t)
		{
			log.error("exception caught processing interfaceDeleted event for " + 
					nodeId + "/" + event.getInterface(), t);
		}
		finally
		{
			if (ownLock)
			{
				if (log.isDebugEnabled())
					log.debug("interfaceDeletedHandler: releasing node lock for nodeid: " + nodeId);
				
				try
				{
					pNode.releaseNodeLock();
				}
				catch (InterruptedException iE)
				{
					log.error("interfaceDeletedHandler: thread interrupted...failed to release lock on nodeId " + nodeId);
				}
			}
		}
	}
	
	/**
	 * Constructor
	 *
	 * @param pollableServices List of all the PollableService objects 
	 * 			  scheduled for polling
	 */
	BroadcastEventProcessor(List pollableServices)
	{
		Category log = ThreadCategory.getInstance(getClass());
		
		// Set the configuration for this event 
		// receiver.
		//
		m_scheduler= Poller.getInstance().getScheduler();
		m_pollableServices    = pollableServices;

		// Create the message selector and subscribe to eventd
		createMessageSelectorAndSubscribe();
		if(log.isDebugEnabled())
			log.debug("Subscribed to eventd");

	}

	/**
	 * Unsubscribe from eventd
	 */
	public void close()
	{
		EventIpcManagerFactory.getInstance().getManager().removeEventListener(this);
	}

	/**
	 * This method is invoked by the EventIpcManager
	 * when a new event is available for processing.
	 * Each message is examined for its Universal Event Identifier
	 * and the appropriate action is taking based on each UEI.
	 *
	 * @param event	The event 
	 */
	public void onEvent(Event event)
	{
		if (event == null)
			return;

		Category log = ThreadCategory.getInstance(getClass());

		// print out the uei
		//
		if(log.isDebugEnabled())
		{
			log.debug("BroadcastEventProcessor: received event, uei = " + event.getUei());
		}

		// If the event doesn't have a nodeId it can't be processed.
		if(!event.hasNodeid())
		{
			log.info("BroadcastEventProcessor: no database node id found, discarding event");
		}
		else if(event.getUei().equals(EventConstants.NODE_GAINED_SERVICE_EVENT_UEI))
		{
			// If there is no interface then it cannot be processed
			//
			if(event.getInterface() == null || event.getInterface().length() == 0)
			{
				log.info("BroadcastEventProcessor: no interface found, discarding event");
			}
			else
			{
				nodeGainedServiceHandler(event);
			}
		}
		else if(event.getUei().equals(EventConstants.INTERFACE_REPARENTED_EVENT_UEI))
		{
			// If there is no interface then it cannot be processed
			//
			if(event.getInterface() == null || event.getInterface().length() == 0)
			{
				log.info("BroadcastEventProcessor: no interface found, discarding event");
			}
			else if(event.getUei().equals(EventConstants.INTERFACE_REPARENTED_EVENT_UEI))
			{
				// If there is no interface then it cannot be processed
				//
				if(event.getInterface() == null || event.getInterface().length() == 0)
				{
					log.info("BroadcastEventProcessor: no interface found, discarding event");
				}
				else
				{
					interfaceReparentedHandler(event);
				}
			}
			else if(event.getUei().equals(EventConstants.NODE_DELETED_EVENT_UEI) ||
				event.getUei().equals(EventConstants.DUP_NODE_DELETED_EVENT_UEI))
			{
				// NEW NODE OUTAGE EVENTS
				nodeDeletedHandler(event);
			}
			else if(event.getUei().equals(EventConstants.INTERFACE_DELETED_EVENT_UEI))
			{
				// If there is no interface then it cannot be processed
				//
				if(event.getInterface() == null || event.getInterface().length() == 0)
				{
					log.info("BroadcastEventProcessor: no interface found, discarding event");
				}
				else
				{
					interfaceDeletedHandler(event);
				}
			}
			
		} //end single event proces

	} // end onEvent()

	/**
	 * Return an id for this event listener
	 */
	public String getName()
	{
		return "Poller:BroadcastEventProcessor";
	}
} // end class
