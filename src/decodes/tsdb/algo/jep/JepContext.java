package decodes.tsdb.algo.jep;

import ilex.util.Logger;

import java.util.Date;

import org.nfunk.jep.JEP;

import decodes.tsdb.TimeSeriesDb;
import decodes.tsdb.algo.AW_AlgorithmBase;

/**
 * JepContext provides a link to the database and the algorithm.
 * It also stores the current time slice date/time if this is being executed
 * within a time slice.
 * It also stores various context flags resulting from execution of cond, else, etc.
 */
public class JepContext
{
	private JEP parser = null;
	private boolean exitCalled = false;
	private boolean lastConditionFailed = false;
	private Date timeSliceBaseTime = null;
	private AW_AlgorithmBase algo = null;
	private TimeSeriesDb tsdb = null;
	private String gotoLabel = null;
	private String onErrorLabel = null;
	private boolean lastStatementWasCond = false;
	
	public JepContext(TimeSeriesDb tsdb, AW_AlgorithmBase algo)
	{
		this.tsdb = tsdb;
		this.algo = algo;
		parser = new JEP();
		parser.addStandardFunctions();
		parser.addStandardConstants();
		parser.setAllowAssignment(true);
		parser.setAllowUndeclared(true);
		parser.addFunction(LookupMetaFunction.funcName, new LookupMetaFunction());
		parser.addFunction(ConditionFunction.funcName, new ConditionFunction(this));
		parser.addFunction(ElseFunction.funcName, new ElseFunction(this));
		parser.addFunction(ExitFunction.funcName, new ExitFunction(this));
		parser.addFunction("debug3", new LogFunction(this, Logger.E_DEBUG3));
		parser.addFunction("debug2", new LogFunction(this, Logger.E_DEBUG2));
		parser.addFunction("debug1", new LogFunction(this, Logger.E_DEBUG1));
		parser.addFunction("info", new LogFunction(this, Logger.E_INFORMATION));
		parser.addFunction("warning", new LogFunction(this, Logger.E_WARNING));
		parser.addFunction(OnErrorFunction.funcName, new OnErrorFunction(this));

		if (tsdb == null || tsdb.isCwms())
			parser.addFunction(RatingFunction.funcName, new RatingFunction(this));
	}

	/**
	 * Call before executing a new expression to reset all flags.
	 */
	public void reset()
	{
		exitCalled = false;
		if (!lastStatementWasCond)
			lastConditionFailed = false;
		lastStatementWasCond = false;
		gotoLabel = null;
	}
	
	public boolean isExitCalled()
	{
		return exitCalled;
	}

	public void setExitCalled(boolean exitCalled)
	{
		this.exitCalled = exitCalled;
	}

	public boolean getLastConditionFailed()
	{
		return lastConditionFailed;
	}

	public void setLastConditionFailed(boolean lastConditionFailed)
	{
		this.lastConditionFailed = lastConditionFailed;
	}

	public Date getTimeSliceBaseTime()
	{
		return timeSliceBaseTime;
	}

	public void setTimeSliceBaseTime(Date timeSliceBaseTime)
	{
		this.timeSliceBaseTime = timeSliceBaseTime;
	}

	public AW_AlgorithmBase getAlgo()
	{
		return algo;
	}

	public TimeSeriesDb getTsdb()
	{
		return tsdb;
	}

	public String getGotoLabel()
	{
		return gotoLabel;
	}

	public void setGotoLabel(String gotoLabel)
	{
		this.gotoLabel = gotoLabel;
	}

	public String getOnErrorLabel()
	{
		return onErrorLabel;
	}

	public void setOnErrorLabel(String onErrorLabel)
	{
		this.onErrorLabel = onErrorLabel;
	}

	public JEP getParser()
	{
		return parser;
	}

	public void setLastStatementWasCond(boolean lastStatementWasCond)
	{
		this.lastStatementWasCond = lastStatementWasCond;
	}
}