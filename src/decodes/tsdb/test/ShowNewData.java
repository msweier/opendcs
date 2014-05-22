/*
*  $Id$
*/
package decodes.tsdb.test;

import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

import ilex.var.TimedVariable;

import decodes.db.DataType;
import decodes.db.SiteName;
import decodes.sql.DbKey;
import decodes.tsdb.*;

/**
Stays in a loop showing new data on the screen as it arrives.
Reads data from the tasklist table.
*/
public class ShowNewData extends TestProg
{
	public ShowNewData()
	{
		super(null);
	}

	public static void main(String args[])
		throws Exception
	{
		TestProg tp = new ShowNewData();
		tp.execute(args);
	}

	protected void runTest()
		throws Exception
	{
		System.out.println("Getting new data for app ID=" + appId);
//		while(true)
//		{
			DataCollection dc = theDb.getNewData(appId);
			List<CTimeSeries> tsl = dc.getAllTimeSeries();
			for(CTimeSeries ts : tsl)
			{
				System.out.println("");
				System.out.println("Time Series  SDI="
					+ ts.getSDI() 
					+ " tabsel=" + ts.getTableSelector()
					+ " interval=" + ts.getInterval()
					+ " modelId=" + ts.getModelId()
					+ " modelRunId=" + ts.getModelRunId()
					+ " compId=" + ts.getComputationId());
				TimeSeriesIdentifier tsid = ts.getTimeSeriesIdentifier();
				if (tsid.getSite() != null)
					for(Iterator<SiteName> snit = tsid.getSite().getNames(); snit.hasNext(); )
					{
						SiteName sn = snit.next();
						System.out.println("Site Name: " + sn);
					}
				DataType dt = tsid.getDataType();
				System.out.println("Data Type: " + dt);
				System.out.println("Number of values: " + ts.size());
				for(int i=0; i<ts.size(); i++)
				{
					TimedVariable tv = ts.sampleAt(i);
					System.out.println(
						(VarFlags.wasAdded(tv) ? "Add: " :
						 VarFlags.wasDeleted(tv) ? "Del: " : "???: ")
						+ tv.toString());
				}
			}
			theDb.releaseNewData(dc);
			try 
			{
				Thread.sleep(1000L); 
				System.out.print(".");
				System.out.flush();
			}
			catch(InterruptedException ex) {}
//		}
	}

	private static CTimeSeries makeTimeSeries(String x)
		throws Exception
	{
		StringTokenizer st = new StringTokenizer(x, ":");
		DbKey sdi = DbKey.createDbKey(Long.parseLong(st.nextToken()));
		String intv = st.nextToken();
		String tabsel = st.nextToken();
		CTimeSeries ret = new CTimeSeries(sdi, intv, tabsel);
		if (st.hasMoreTokens())
		{
			int modid = Integer.parseInt(st.nextToken());
			ret.setModelRunId(modid);
		}
		return ret;
	}
}