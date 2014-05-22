/*
*  $Id$
*
*  This is open-source software written by ILEX Engineering, Inc., under
*  contract to the federal government. You are free to copy and use this
*  source code for your own purposes, except that no part of the information
*  contained in this file may be claimed to be proprietary.
*
*  Except for specific contractual terms between ILEX and the federal 
*  government, this source code is provided completely without warranty.
*  For more information contact: info@ilexeng.com
*  
*  $Log$
*  Revision 1.28  2013/07/12 11:50:53  mmaloney
*  Added tasklist queue stuff.
*
*  Revision 1.27  2013/07/09 19:01:24  mmaloney
*  If database goes away and reconnection is done, also recreate the resolver.
*
*  Revision 1.26  2013/03/28 19:07:24  mmaloney
*  Implement cmd line arg -O OfficeID
*
*  Revision 1.25  2013/03/25 18:15:03  mmaloney
*  Refactor starting event server.
*
*  Revision 1.24  2013/03/25 17:08:43  mmaloney
*  event port fix
*
*  Revision 1.23  2013/03/25 16:58:26  mmaloney
*  Refactor comp lock stale time.
*
*  Revision 1.22  2013/03/21 18:27:39  mmaloney
*  DbKey Implementation
*
*/
package decodes.tsdb;

import java.io.IOException;
import java.util.List;
import java.net.InetAddress;

import opendcs.dai.LoadingAppDAI;
import opendcs.dai.TimeSeriesDAI;

import lrgs.gui.DecodesInterface;

import ilex.cmdline.BooleanToken;
import ilex.cmdline.StringToken;
import ilex.cmdline.TokenOptions;
import ilex.util.EnvExpander;
import ilex.util.Logger;

import decodes.sql.DbKey;
import decodes.util.CmdLineArgs;
import decodes.util.DecodesException;
import decodes.util.DecodesSettings;

/**
ComputationApp is the main module for the background comp processor.
*/
public class ComputationApp
	extends TsdbAppTemplate
{
	/** Holds app name, id, & description. */
	CompAppInfo appInfo;

	/** My lock */
	private TsdbCompLock myLock;
	
	/** My resolver */
	private DbCompResolver myResolver;
	
	private boolean shutdownFlag;

	private int pid;
	private String hostname;
	private int compsTried = 0;
	private int compErrors = 0;
	private int evtPort = -1;
	
	private BooleanToken regressionTestModeArg = new BooleanToken("T", "Regression Test Mode",
		"", TokenOptions.optSwitch, false);
	private StringToken officeIdArg = new StringToken(
		"O", "OfficeID", "", TokenOptions.optSwitch, "");
	

	/**
	 * Constructor called from main method after parsing arguments.
	 */
	public ComputationApp()
	{
		super("compproc.log");
		myLock = null;
		myResolver = null;
		shutdownFlag = false;
	}

	/** @return the application ID. */
	public DbKey getAppId() { return appId; }

	protected void addCustomArgs(CmdLineArgs cmdLineArgs)
	{
		cmdLineArgs.addToken(regressionTestModeArg);
		appNameArg.setDefaultValue("compproc");
		cmdLineArgs.addToken(officeIdArg);
	}

	/**
	 * Sets the application ID. 
	 * @param id the ID.
	 */
	public void setAppId(DbKey id) { this.appId = id; }

	/** @return the application name. */
	public String getAppName() 
	{
		return appInfo.getAppName(); 
	}

	/** @return the application comment. */
	public String getAppComment() 
	{
		return appInfo.getComment(); 
	}

	/**
	 * The application run method. Called after all initialization methods
	 * by the base class.
	 * @throws LockBusyException if another process has the lock
	 * @throws DbIoException on failure to access the database
	 * @throws NoSuchObjectException if the application is invalid.
	 */
	public void runApp( )
		throws LockBusyException, DbIoException, NoSuchObjectException
	{
		initialize();
		Logger.instance().info("============== CompApp " + getAppName() 
			+", appId=" + appId + " Starting ==============");

		long lastDataTime = System.currentTimeMillis();
		while(!shutdownFlag)
		{
			if (theDb == null)
			{
				try 
				{
					createDatabase();
					myResolver = new DbCompResolver(theDb);
				}
				catch(Exception ex)
				{
					Logger.instance().fatal("Cannot create database interface: "
						+ ex);
					shutdownFlag = true;
					continue;
				}
			}

			// If not connected, attempt to connect, wait 10 sec between tries.
			if (!theDb.isConnected())
			{
				if (!tryConnect())
				{
					closeDb();
					try { Thread.sleep(10000L); }
					catch(InterruptedException ex) {}
					continue;
				}
				// New connection, need to obtain new lock & load resolver.
				myLock = null;
			}
			String action="";
			TimeSeriesDAI timeSeriesDAO = theDb.makeTimeSeriesDAO();
			LoadingAppDAI loadingAppDAO = theDb.makeLoadingAppDAO();
			try
			{
				// Make sure this process's lock is still valid.
				action = "Checking lock";
				
				if (myLock == null)
					myLock = loadingAppDAO.obtainCompProcLock(appInfo, pid, hostname); 
				else
				{
					setAppStatus("Cmps: " + compsTried + "/" + compErrors);
					loadingAppDAO.checkCompProcLock(myLock);
				}

				// Periodically reload my list of computations.
//				long now = System.currentTimeMillis();

				action = "Getting new data";
				DataCollection data = theDb.getNewData(appId);
				
				// In Regression Test Mode, exit after 5 sec of idle
				if (!data.isEmpty())
					lastDataTime = System.currentTimeMillis();
				else if (regressionTestModeArg.getValue()
				 && System.currentTimeMillis() - lastDataTime > 10000L)
				{
					Logger.instance().info("Regression Test Mode - Exiting after 10 sec idle.");
					shutdownFlag = true;
					loadingAppDAO.releaseCompProcLock(myLock);
				}

				action = "Resolving computations";
				DbComputation comps[] = myResolver.resolve(data);

				action = "Applying computations";
				for(DbComputation comp : comps)
				{
					Logger.instance().debug1("Trying computation '" 
						+ comp.getName() + "' #trigs=" + comp.getTriggeringRecNums().size());
					compsTried++;
					try
					{
						comp.prepareForExec(theDb);
						comp.apply(data, theDb);
					}
					catch(NoSuchObjectException ex)
					{
						compErrors++;
						warning("Computation '" + comp.getName()
							+ "removed from DB: " + ex);
					}
					catch(DbCompException ex)
					{
						String msg = "Computation '" + comp.getName() 
							+ "' DbCompException: " + ex;
						warning(msg);
						compErrors++;
						for(Integer rn : comp.getTriggeringRecNums())
							 data.getTasklistHandle().markComputationFailed(rn);
					}
					catch(Exception ex)
					{
						compErrors++;
						String msg = "Computation '" + comp.getName() 
							+ "' Exception: " + ex;
						warning(msg);
						System.err.println(msg);
						ex.printStackTrace(System.err);
						for(Integer rn : comp.getTriggeringRecNums())
							 data.getTasklistHandle().markComputationFailed(rn);
					}
					comp.getTriggeringRecNums().clear();
					Logger.instance().debug1("End of computation '" 
						+ comp.getName() + "'");
				}

				action = "Saving results";
				List<CTimeSeries> tsList = data.getAllTimeSeries();
Logger.instance().debug3(action + " " + tsList.size() +" time series in data.");
				for(CTimeSeries ts : tsList)
				{
					try { timeSeriesDAO.saveTimeSeries(ts); }
					catch(BadTimeSeriesException ex)
					{
						warning("Cannot save time series " + ts.getNameString()
							+ ": " + ex);
					}
				}

				action = "Releasing new data";
				theDb.releaseNewData(data);
			}
			catch(LockBusyException ex)
			{
				Logger.instance().fatal("No Lock - Application exiting: " + ex);
				shutdownFlag = true;
			}
			catch(DbIoException ex)
			{
				warning("Database Error while " + action + ": " + ex);
				theDb.closeConnection();
			}
			finally
			{
				timeSeriesDAO.close();
				loadingAppDAO.close();
			}
			try { Thread.sleep(1000L); }
			catch(InterruptedException ex) {}
		}
		theDb.closeConnection();
		theDb.closeTasklistQueue();
		System.exit(0);
	}

	/** Initialization phase -- any error is fatal. */
	private void initialize()
		throws DbIoException, NoSuchObjectException
	{
		LoadingAppDAI loadingAppDao = theDb.makeLoadingAppDAO();
		try
		{
			appInfo = loadingAppDao.getComputationApp(appId);

			// Determine process ID. Note -- We can't really do this in Java
			// without assuming a particular OS. Therefore, we rely on the
			// script that started us to set an environment variable PPID
			// for parent-process-ID. If not present, we default to 1.
			pid = 1;
			String ppids = System.getProperty("PPID");
			if (ppids != null)
			{
				try { pid = Integer.parseInt(ppids); }
				catch(NumberFormatException ex) { pid = 1; }
			}

			try { hostname = InetAddress.getLocalHost().getHostName(); }
			catch(Exception e) { hostname = "unknown"; }

			// Construct the resolver & load it.
			myResolver = new DbCompResolver(theDb);
			
			// Look for EventPort and EventPriority properties. If found,
			String evtPorts = appInfo.getProperty("EventPort");
			if (evtPorts != null)
			{
				try 
				{
					evtPort = Integer.parseInt(evtPorts.trim());
					CompEventSvr compEventSvr = new CompEventSvr(evtPort);
					compEventSvr.startup();
				}
				catch(NumberFormatException ex)
				{
					Logger.instance().warning("App Name " + getAppName()
						+ ": Bad EventPort property '" + evtPorts
						+ "' must be integer. -- ignored.");
				}
				catch(IOException ex)
				{
					Logger.instance().failure(
						"Cannot create Event server: " + ex
						+ " -- no events available to external clients.");
				}
			}
		}
		catch(NoSuchObjectException ex)
		{
			Logger.instance().fatal("App Name " + getAppName() + ": " + ex);
			throw ex;
		}
		catch(DbIoException ex)
		{
			Logger.instance().fatal("App Name " + getAppName() + ": " + ex);
			throw ex;
		}
		finally
		{
			loadingAppDao.close();
		}
		
		if (officeIdArg.getValue() != null && officeIdArg.getValue().length() > 0)
			DecodesSettings.instance().CwmsOfficeId = officeIdArg.getValue();
		
//		String tasklistQueueDir = appInfo.getProperty("tasklistQueueDir");
//		if (tasklistQueueDir != null)
//		{
//			String s = appInfo.getProperty("tasklistQueueThreshold");
//			int thresh = 8; // hours
//			if (s != null)
//			{
//				try { thresh = Integer.parseInt(s); }
//				catch(NumberFormatException ex)
//				{
//					warning("Invalid tasklistQueueThreshold property '"
//						+ s + "' -- using default of " + thresh + " hours.");
//				}
//			}
//			String dir = EnvExpander.expand(tasklistQueueDir);
//			try
//			{
//				theDb.useTasklistQueue(new TasklistQueueFile(dir, appInfo.getAppName()),
//					thresh);
//			}
//			catch (IOException ex)
//			{
//				warning("Cannot create tasklist queue file in directory '"
//					+ dir + "' for application '" + appInfo.getAppName()
//					+ "': " + ex);
//			}
//		}
	}
	
	public void initDecodes()
		throws DecodesException
	{
		DecodesInterface.silent = true;
		if (DecodesInterface.isInitialized())
			return;
		DecodesInterface.initDecodesMinimal(cmdLineArgs.getPropertiesFile());
	}

	/**
	 * The main method.
	 * @param args command line arguments.
	 */
	public static void main( String[] args )
		throws Exception
	{
		ComputationApp compApp = new ComputationApp();
		compApp.execute(args);
	}

	private void warning(String x)
	{
		Logger.instance().warning("CompApp(" + appId + "): " + x);
	}

	/**
	 * Sets the application's status string in its database lock.
	 */
	public void setAppStatus(String status)
	{
		if (myLock != null)
			myLock.setStatus(status);
	}
}
