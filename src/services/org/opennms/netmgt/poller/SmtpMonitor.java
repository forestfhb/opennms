//
// Copyright (C) 2002-2003 Sortova Consulting Group, Inc.  All rights reserved.
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
//
package org.opennms.netmgt.poller;

import java.lang.*;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.nio.channels.SocketChannel;
import org.opennms.netmgt.utils.SocketChannelUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;

import java.util.Map;
import java.util.StringTokenizer;

import org.apache.log4j.Priority;
import org.apache.log4j.Category;

import org.apache.regexp.RE;
import org.apache.regexp.RESyntaxException;

import org.opennms.core.utils.ThreadCategory;
import org.opennms.netmgt.utils.ParameterMap;

/**
 * <P>This class is designed to be used by the service poller
 * framework to test the availability of the SMTP service on 
 * remote interfaces. The class implements the ServiceMonitor
 * interface that allows it to be used along with other
 * plug-ins by the service poller framework.</P>
 *
 * @author <A HREF="mailto:tarus@opennms.org">Tarus Balog</A>
 * @author <A HREF="http://www.opennms.org/">OpenNMS</A>
 *
 */
final class SmtpMonitor
        extends IPv4LatencyMonitor
{
	/** 
	 * Default SMTP port.
	 */
	private static final int DEFAULT_PORT = 25;

	/** 
	 * Default retries.
	 */
	private static final int DEFAULT_RETRY = 0;

	/** 
	 * Default timeout.  Specifies how long (in milliseconds) to block waiting
	 * for data from the monitored interface.
	 */
	private static final int DEFAULT_TIMEOUT = 3000;

	/**
	 * The name of the local host.
	 */
	private static String 	LOCALHOST_NAME;

	/**
	 * Used to check for a multiline response. A multline response begins
	 * with the same 3 digit response code, but has a hypen after the last
	 * number instead of a space.
	 */
	private static RE		MULTILINE	= null;
	
	/**
	 * Used to check for the end of a multiline response. The end of a multiline
	 * response is the same 3 digit response code followed by a space
	 */
	private RE		ENDMULTILINE	= null;
	
	// Init the local host and MULTILINE
	//
	static
	{
		try
		{
			LOCALHOST_NAME = InetAddress.getLocalHost().getHostName();
		}
		catch(UnknownHostException uhE)
		{
			ThreadCategory.getInstance(SmtpMonitor.class).error("Failed to resolve localhost name, using localhost");
			LOCALHOST_NAME = "localhost";
		}
		
		try
		{
			MULTILINE = new RE("^[0-9]{3}-");
		}
		catch(RESyntaxException ex)
		{
			throw new java.lang.reflect.UndeclaredThrowableException(ex);
		}
	}

	/**
	 * <P>Poll the specified address for SMTP service availability.</P>
	 *
	 * <P>During the poll an attempt is made to connect on the specified
	 * port (by default TCP port 25).  If the connection request is
	 * successful, the banner line generated by the interface is parsed
	 * and if the extracted return code indicates that we are talking to
	 * an SMTP server we continue.  Next, an SMTP 'HELO' command is sent
	 * to the interface.  Again the response is parsed and a return code
	 * extracted and verified.  Finally, an SMTP 'QUIT' command is sent.
	 * Provided that the interface's response is valid we set the
	 * service status to SERVICE_AVAILABLE and return.</P>
	 *
	 * @param iface		The network interface to test the service on.
	 * @param parameters	The package parameters (timeout, retry, etc...) to be 
	 *  used for this poll.
	 *
	 * @return The availibility of the interface and if a transition event
	 * 	should be supressed.
	 *
	 */
	public int poll(NetworkInterface iface, Map parameters) 
	{
		// Get interface address from NetworkInterface
		//
		if (iface.getType() != iface.TYPE_IPV4)
			throw new NetworkInterfaceNotSupportedException("Unsupported interface type, only TYPE_IPV4 currently supported");

		// Process parameters
		//
		Category log = ThreadCategory.getInstance(getClass());


		int retry  = ParameterMap.getKeyedInteger(parameters, "retry", DEFAULT_RETRY);
		int timeout= ParameterMap.getKeyedInteger(parameters, "timeout", DEFAULT_TIMEOUT);
		int port = ParameterMap.getKeyedInteger(parameters, "port", DEFAULT_PORT);
                String rrdPath = ParameterMap.getKeyedString(parameters, "rrd-repository", null);
                String dsName = ParameterMap.getKeyedString(parameters, "ds-name", null);

                if (rrdPath == null)
                {
                        log.info("poll: RRD repository not specified in parameters, latency data will not be stored.");
                }
                if (dsName == null)
                {
                        dsName = DS_NAME;
                }


		// Get interface address from NetworkInterface
		//
		InetAddress ipv4Addr = (InetAddress)iface.getAddress();

		if(log.isDebugEnabled())
			log.debug("poll: address = " + ipv4Addr.getHostAddress()
				  + ", port = " + port
				  + ", timeout = " + timeout
				  + ", retry = " + retry);
		
		int serviceStatus = ServiceMonitor.SERVICE_UNAVAILABLE;
                long responseTime = -1;

		for (int attempts=0; attempts <= retry && serviceStatus != ServiceMonitor.SERVICE_AVAILABLE; attempts++)
		{
                SocketChannel sChannel = null;
			try
			{
				// create a connected socket
				//
                                long sentTime = System.currentTimeMillis();

                                sChannel = SocketChannelUtil.getConnectedSocketChannel(ipv4Addr, port, timeout);
                                if (sChannel == null)
                                {
                                        log.debug("SmtpMonitor: did not connect to host within timeout: " + timeout +" attempt: " + attempts);
                                        continue;
                                }
                                log.debug("SmtpMonitor: connected to host: " + ipv4Addr + " on port: " + port);

				// We're connected, so upgrade status to unresponsive
				serviceStatus = SERVICE_UNRESPONSIVE;

                                BufferedReader rdr = new BufferedReader(new InputStreamReader(sChannel.socket().getInputStream()));

				
				//
				// Tokenize the Banner Line, and check the first 
				// line for a valid return.
				//
				String banner = rdr.readLine();

				if (banner == null)
					continue;							
				if (MULTILINE.match(banner))
				{
					// Ok we have a multi-line response...first three
					// chars of the response line are the return code.
					// The last line of the response will start with 
					// return code followed by a space.
					String multiLineRC = new String(banner.getBytes(), 0, 3) + " ";
								
					// Create new regExp to look for last line
					// of this mutli line response
					try
					{
						ENDMULTILINE = new RE(multiLineRC);
					}
					catch(RESyntaxException ex)
					{
						throw new java.lang.reflect.UndeclaredThrowableException(ex);
					}
					
					// read until we hit the last line of the multi-line
					// response
				do
				{
					banner = rdr.readLine();
					} while (banner != null && !ENDMULTILINE.match(banner));
					if (banner == null)
						continue;
				}

				if(log.isDebugEnabled())
					log.debug("poll: banner = " + banner);

				StringTokenizer t = new StringTokenizer(banner);
				int rc = Integer.parseInt(t.nextToken());
				if(rc == 220)
				{
					//
					// Send the HELO command
					//
					String cmd = "HELO " + LOCALHOST_NAME + "\r\n";
                                        sChannel.socket().getOutputStream().write(cmd.getBytes());

					
					//
					// get the returned string, tokenize, and 
					// verify the correct output.
					//
					String response = rdr.readLine();
	                                responseTime = System.currentTimeMillis() - sentTime;

					if (response == null)
						continue;							
					if (MULTILINE.match(response))
					{
						// Ok we have a multi-line response...first three
						// chars of the response line are the return code.
						// The last line of the response will start with 
						// return code followed by a space.
						String multiLineRC = new String(response.getBytes(), 0, 3) + " ";
									
						// Create new regExp to look for last line
						// of this mutli line response
						try
						{
							ENDMULTILINE = new RE(multiLineRC);
						}
						catch(RESyntaxException ex)
						{
							throw new java.lang.reflect.UndeclaredThrowableException(ex);
						}
							
						// read until we hit the last line of the multi-line
						// response
					do
					{
						response = rdr.readLine();
						} while (response != null && !ENDMULTILINE.match(response));
						if (response == null)
							continue;
					}
					
					t = new StringTokenizer(response);
					rc = Integer.parseInt(t.nextToken());
					if(rc == 250)
					{
						cmd = "QUIT\r\n";
	                                        sChannel.socket().getOutputStream().write(cmd.getBytes());

					
						//
						// get the returned string, tokenize, and 
						// verify the correct output.
						//
						response = rdr.readLine();
						if (response == null)
							continue;
						if (MULTILINE.match(response))
						{
							// Ok we have a multi-line response...first three
							// chars of the response line are the return code.
							// The last line of the response will start with 
							// return code followed by a space.
							String multiLineRC = new String(response.getBytes(), 0, 3) + " ";
									
							// Create new regExp to look for last line
							// of this mutli line response
							try
							{
								ENDMULTILINE = new RE(multiLineRC);
							}
							catch(RESyntaxException ex)
							{
								throw new java.lang.reflect.UndeclaredThrowableException(ex);
							}
							
							// read until we hit the last line of the multi-line
							// response
						do
						{
							response = rdr.readLine();
							} while (response != null && !ENDMULTILINE.match(response));
							if (response == null)
								continue;
						}

						t = new StringTokenizer(response);
						rc = Integer.parseInt(t.nextToken());
						
						if(rc == 221)
						{
							serviceStatus = ServiceMonitor.SERVICE_AVAILABLE;
                        		                // Store response time in RRD
        		                                if (responseTime >= 0 && rrdPath != null)
	                                	                this.updateRRD(m_rrdInterface, rrdPath, ipv4Addr, dsName, responseTime);
						}
					}
				}
				
				// If we get this far and the status has not been set
				// to available, then something didn't verify during
				// the banner checking or HELO/QUIT comand process.
				if (serviceStatus != ServiceMonitor.SERVICE_AVAILABLE)
				{
					serviceStatus = ServiceMonitor.SERVICE_UNAVAILABLE;
				}
			}
			catch(NumberFormatException e)
			{
				// Ignore
				e.fillInStackTrace();
				if(log.isDebugEnabled())
					log.debug("poll: NumberFormatException while polling address " + ipv4Addr.getHostAddress(), e);
			}
			catch(NoRouteToHostException e)
			{
				e.fillInStackTrace();
				if(log.isEnabledFor(Priority.WARN))
					log.warn("poll: No route to host exception for address " + ipv4Addr.getHostAddress(), e);
				break; // Break out of for(;;)
			}
                        catch(InterruptedException e)
                        {
                                // Ignore
                                e.fillInStackTrace();
                                if(log.isDebugEnabled())
                                        log.debug("SmtpMonitor: Interrupted exception for address: " + ipv4Addr, e);
                        }
			catch(ConnectException e)
			{
				// Ignore
				e.fillInStackTrace();
				if(log.isDebugEnabled())
					log.debug("poll: Connection exception for address " + ipv4Addr.getHostAddress(), e);
			}
			catch(IOException e)
			{
				// Ignore
				e.fillInStackTrace();
				if(log.isDebugEnabled())
					log.debug("poll: IOException while polling address " + ipv4Addr.getHostAddress(), e);
			}
			finally
			{
				try
				{
					// Close the socket
                                        if(sChannel != null)
                                                sChannel.close();
				}
				catch(IOException e) 
				{ 
					e.fillInStackTrace();
					if(log.isDebugEnabled())
						log.debug("poll: Error closing socket.",e);
				}
			}
		}
	
		//
		// return the status of the service
		//
		return serviceStatus;
	}
}

