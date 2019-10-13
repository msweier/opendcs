/*
 * This software was written by Cove Software, LLC. under contract to the 
 * U.S. Government. This software is property of the U.S. Government and 
 * may be used by permission only.
 * 
 * No warranty is provided or implied other than specific contractual terms.
 * 
 * Copyright 2014 U.S. Army Corps of Engineers, Hydrologic Engineering Center.
 * All rights reserved.
 */
package decodes.tsdb.test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.TimeZone;

import opendcs.dai.DacqEventDAI;
import opendcs.dai.DeviceStatusDAI;
import opendcs.dai.SiteDAI;
import opendcs.dai.TimeSeriesDAI;
import opendcs.opentsdb.OpenTimeSeriesDAO;
import opendcs.opentsdb.OpenTsdb;
import opendcs.opentsdb.StorageTableSpec;
import decodes.cwms.CwmsTimeSeriesDb;
import decodes.cwms.CwmsTsId;
import decodes.db.Database;
import decodes.db.DatabaseException;
import decodes.db.Platform;
import decodes.db.Site;
import decodes.db.SiteList;
import decodes.db.SiteName;
import decodes.polling.DacqEvent;
import decodes.polling.DeviceStatus;
import decodes.sql.DbKey;
import decodes.sql.SqlDatabaseIO;
import decodes.tsdb.DeleteTs;
import decodes.tsdb.DisableComps;
import decodes.tsdb.TsdbAppTemplate;
import decodes.util.CmdLineArgs;
import decodes.util.DecodesSettings;
import ilex.util.CmdLine;
import ilex.util.CmdLineProcessor;
import ilex.util.Logger;
import ilex.util.TextUtil;

/**
 * General purpose database command-line utility
 * @author mmaloney
 *
 */
public class TestRunner extends TsdbAppTemplate
{
	private CmdLineProcessor cmdLineProc = new CmdLineProcessor();
	private Date since, until;
	private TimeZone tz = TimeZone.getTimeZone("UTC");
	private ArrayList<String> tsids = new ArrayList<String>();
	private SimpleDateFormat sdf = new SimpleDateFormat("dd-MMM-yyyy/HH:mm");
	private String appName = "compproc_regtest";
	private String outputName = "output";
	private PrintStream outs = null;
	private String presGrp = null;
	
	private CmdLine tsidsCmd = 
		new CmdLine("tsids", "[list-of-tsids]")
		{
			public void execute(String[] tokens)
			{
				if (tokens.length < 2)
				{
					usage();
					return;
				}
				for(int idx=1; idx < tokens.length; idx++)
					tsids.add(tokens[idx]);
			}
		};
		
	private CmdLine tzCmd =
		new CmdLine("tz", 
			"[tzid] - set timezone for since & until")
		{
			public void execute(String[] tokens)
			{
				tz = TimeZone.getTimeZone(tokens[1]);
				sdf.setTimeZone(tz);
				System.out.println("Timezone set to " + tz.getID());
			}
		};
		
	private CmdLine sinceCmd =
		new CmdLine("since", 
			"[dd-MMM-yyy/HH:mm] - set since time for test")
		{
			public void execute(String[] tokens)
			{
				if (tokens.length < 2)
				{
					since = null;
					return;
				}
				try
				{
					since = sdf.parse(tokens[1]);
				}
				catch (ParseException e)
				{
					e.printStackTrace();
				}
			}
		};
	private CmdLine untilCmd =
		new CmdLine("until", 
			"[dd-MMM-yyy/HH:mm] - set until time for test")
		{
			public void execute(String[] tokens)
			{
				if (tokens.length < 2)
				{
					until = null;
					return;
				}

				try
				{
					until = sdf.parse(tokens[1]);
				}
				catch (ParseException e)
				{
					e.printStackTrace();
				}
			}
		};

	private CmdLine procCmd =
		new CmdLine("proc", "[compproc-app-name]")
		{
			public void execute(String[] tokens)
			{
				appName = tokens[1];
			}
		};
		

	private CmdLine sleepCmd =
		new CmdLine("sleep", "[number-of-seconds]")
		{
			public void execute(String[] tokens)
			{
				try
				{
					int n = Integer.parseInt(tokens[1]);
					Thread.sleep(n * 1000L);
				}
				catch(NumberFormatException ex)
				{
					ex.printStackTrace();
					return;
				}
				catch (InterruptedException e)
				{
					e.printStackTrace();
				}
			}
		};
		
		
	private CmdLine logCmd =	
		new CmdLine("log", "message to be logged.")
		{
			public void execute(String[] tokens)
			{
				StringBuilder sb = new StringBuilder();
				for(int idx = 1; idx < tokens.length; idx++)
					sb.append(tokens[idx] + " ");
				Logger.instance().info(sb.toString());
			}
		};
		
		
	private CmdLine outputCmd =	
		new CmdLine("output", "[name-of-output-file]")
		{
			public void execute(String[] tokens)
			{
				try
				{
					outs = new PrintStream(new File(outputName = tokens[1]));
				}
				catch (FileNotFoundException e)
				{
					e.printStackTrace();
				}
			}
		};
		
	private CmdLine presgroupCmd =	
		new CmdLine("presgroup", "[name of presentation group]")
		{
			public void execute(String[] tokens)
			{
				presGrp = tokens[1];
			}
		};
		
	private CmdLine disablecompsCmd =	
		new CmdLine("disablecomps", " -- Disable computations for the named comp proc App")
		{
			public void execute(String[] tokens)
			{
				disableComps(tokens);
			}
		};
		
	private CmdLine deletetsCmd =	
		new CmdLine("deletets", "[list blank-separated of TSIDs] - Delete TS data between since & until")
		{
			public void execute(String[] tokens)
			{
				deleteTs(tokens);
			}
		};
		
	private CmdLine flushtriggersCmd =	
		new CmdLine("flushtriggers", "Delete any tasklist entries for this app ID.")
		{
			public void execute(String[] tokens)
			{
				flushtriggers(tokens);
			}
		};
	
		
		
		
		
	public TestRunner()
	{
		super("util.log");
	}

	@Override
	protected void runApp() throws Exception
	{
		outs = new PrintStream(new File(outputName));
		cmdLineProc.addCmd(tsidsCmd);
		cmdLineProc.addCmd(tzCmd);
		cmdLineProc.addCmd(sinceCmd);
		cmdLineProc.addCmd(untilCmd);
		cmdLineProc.addCmd(procCmd);
		cmdLineProc.addCmd(sleepCmd);
		cmdLineProc.addCmd(logCmd);
		cmdLineProc.addCmd(outputCmd);
		cmdLineProc.addCmd(presgroupCmd);
		cmdLineProc.addCmd(disablecompsCmd);
		cmdLineProc.addCmd(deletetsCmd);
		
		cmdLineProc.addHelpAndQuitCommands();
		
		cmdLineProc.prompt = "cmd: ";
		cmdLineProc.processInput();
	}

	
	/**
	 * @param args
	 */
	public static void main(String[] args)
		throws Exception
	{
		TestRunner dbUtil = new TestRunner();
		dbUtil.execute(args);
	}
	
	@Override
	protected void addCustomArgs(CmdLineArgs cmdLineArgs)
	{
	}

	protected void disableComps(String[] tokens)
	{
		DisableComps subApp = 
			new DisableComps()
			{
				@Override
				public void createDatabase() {}
				
				@Override
				public void tryConnect() {}

			};
		
		subApp.getCmdLineArgs().setNoInit(true);
		try
		{
			subApp.execute(tokens);
		}
		catch (Exception e)
		{
			System.err.println("Error executing cmd '" + tokens[0] + "': " + e);
			e.printStackTrace(System.err);
		}
	}
	
	protected void deleteTs(String[] tokens)
	{
		DeleteTs subApp = 
			new DeleteTs()
			{
				@Override
				public void createDatabase() {}
				
				@Override
				public void tryConnect() {}

			};
			
		subApp.getCmdLineArgs().setNoInit(true);
		try
		{
			subApp.execute(tokens);
		}
		catch (Exception e)
		{
			System.err.println("Error executing cmd '" + tokens[0] + "': " + e);
			e.printStackTrace(System.err);
		}
	}
	
	protected void flushtriggers(String[] tokens)
	{
		ShowNewData subApp = 
			new ShowNewData()
			{
				@Override
				public void createDatabase() {}
				
			};
				
			subApp.setOut(Logger.instance().getLogOutput());
			subApp.getCmdLineArgs().setNoInit(true);
			try
			{
				subApp.execute(tokens);
			}
			catch (Exception e)
			{
				System.err.println("Error executing cmd '" + tokens[0] + "': " + e);
				e.printStackTrace(System.err);
			}
	}

	

}