/**
 * $Id$
 * 
 * $Log$
 * Revision 1.8  2012/11/20 21:17:18  mmaloney
 * Implemented cache for ratings.
 *
 * Revision 1.7  2012/11/20 19:50:00  mmaloney
 * dev
 *
 * Revision 1.6  2012/11/20 16:29:52  mmaloney
 * fixed typos in variable names.
 *
 * Revision 1.5  2012/11/12 20:13:52  mmaloney
 * Do the rating in the time slice method. Not after.
 *
 * Revision 1.4  2012/11/12 19:36:04  mmaloney
 * Use version of method that passes officeID.
 * The one without office ID always returns a RatingSpec with no Ratings in it.
 *
 * Revision 1.3  2012/11/09 21:50:24  mmaloney
 * fixed init
 *
 * Revision 1.2  2012/11/09 21:10:42  mmaloney
 * Fixed imports.
 *
 * Revision 1.1  2012/11/09 21:06:20  mmaloney
 * Checked in Rating Algorithms.
 *
 *
 */
package decodes.cwms.rating;

import java.util.Date;

import ilex.util.EnvExpander;
import ilex.util.Logger;
import ilex.var.NamedVariableList;
import ilex.var.NamedVariable;
import decodes.cwms.CwmsTimeSeriesDb;
import decodes.tsdb.DbAlgorithmExecutive;
import decodes.tsdb.DbCompException;
import decodes.tsdb.DbIoException;
import decodes.tsdb.VarFlags;
import decodes.tsdb.algo.AWAlgoType;
import decodes.tsdb.CTimeSeries;
import decodes.tsdb.ParmRef;
import ilex.var.TimedVariable;


//AW:IMPORTS
import hec.data.RatingException;
import hec.data.cwmsRating.RatingSet;
import java.util.ArrayList;
import decodes.tsdb.TimeSeriesIdentifier;
//AW:IMPORTS_END

//AW:JAVADOC
/**
Implements CWMS rating computations.
Uses the CWMS API provided by HEC to do the rating.
*/
//AW:JAVADOC_END
public class CwmsRatingMultIndep
	extends decodes.tsdb.algo.AW_AlgorithmBase
{
//AW:INPUTS
	public double indep1;	//AW:TYPECODE=i
	public double indep2;	//AW:TYPECODE=i
	public double indep3;	//AW:TYPECODE=i
	public double indep4;	//AW:TYPECODE=i
	public double indep5;	//AW:TYPECODE=i
	public double indep6;	//AW:TYPECODE=i
	public double indep7;	//AW:TYPECODE=i
	public double indep8;	//AW:TYPECODE=i
	public double indep9;	//AW:TYPECODE=i

	String _inputNames[] = { "indep1", "indep2", "indep3", "indep4", "indep5", 
		"indep6", "indep7", "indep8", "indep9" };
//AW:INPUTS_END

//AW:LOCALVARS
	RatingSet ratingSet = null;
	Date beginTime = null;
	Date endTime = null;
	ArrayList<Long> indepTimes = new ArrayList<Long>();
	ArrayList<Double> indep1Values = new ArrayList<Double>();
	ArrayList<Double> indep2Values = null;
	ArrayList<Double> indep3Values = null;
	ArrayList<Double> indep4Values = null;
	ArrayList<Double> indep5Values = null;
	ArrayList<Double> indep6Values = null;
	ArrayList<Double> indep7Values = null;
	ArrayList<Double> indep8Values = null;
	ArrayList<Double> indep9Values = null;
	int numIndeps = 1;
	
	private String buildIndepSpec()
		throws DbCompException
	{
		ParmRef parmRef = getParmRef("indep1");
		if (parmRef == null)
			throw new DbCompException("No time series mapped to indep1");
		TimeSeriesIdentifier tsid = parmRef.timeSeries.getTimeSeriesIdentifier();
		if (tsid == null)
			throw new DbCompException("No time series identifier associated with indep1");
		
		String specId = tsid.getSiteName() + "." + tsid.getDataType().getCode();
		
		parmRef = getParmRef("indep2");
		if (parmRef == null || parmRef.timeSeries == null
		 || parmRef.timeSeries.getTimeSeriesIdentifier() == null)
		{
			if (!indep2_MISSING.equalsIgnoreCase("ignore"))
				throw new DbCompException("No time series mapped to indep2");
			
			return specId;
		}
		tsid = parmRef.timeSeries.getTimeSeriesIdentifier();
		specId = specId + "," + tsid.getDataType().getCode();
		indep2Values = new ArrayList<Double>();
		numIndeps = 2;
		
		parmRef = getParmRef("indep3");
		if (parmRef == null || parmRef.timeSeries == null
		 || parmRef.timeSeries.getTimeSeriesIdentifier() == null)
		{
			if (!indep3_MISSING.equalsIgnoreCase("ignore"))
				throw new DbCompException("No time series mapped to indep3");
			return specId;
		}
		tsid = parmRef.timeSeries.getTimeSeriesIdentifier();
		specId = specId + "," + tsid.getDataType().getCode();
		indep3Values = new ArrayList<Double>();
		numIndeps = 3;

		parmRef = getParmRef("indep4");
		if (parmRef == null || parmRef.timeSeries == null
		 || parmRef.timeSeries.getTimeSeriesIdentifier() == null)
		{
			if (!indep4_MISSING.equalsIgnoreCase("ignore"))
				throw new DbCompException("No time series mapped to indep4");
			
			return specId;
		}
		tsid = parmRef.timeSeries.getTimeSeriesIdentifier();
		specId = specId + "," + tsid.getDataType().getCode();
		indep4Values = new ArrayList<Double>();
		numIndeps = 4;

		parmRef = getParmRef("indep5");
		if (parmRef == null || parmRef.timeSeries == null
		 || parmRef.timeSeries.getTimeSeriesIdentifier() == null)
		{
			if (!indep5_MISSING.equalsIgnoreCase("ignore"))
				throw new DbCompException("No time series mapped to indep5");
			
			return specId;
		}
		tsid = parmRef.timeSeries.getTimeSeriesIdentifier();
		specId = specId + "," + tsid.getDataType().getCode();
		indep5Values = new ArrayList<Double>();
		numIndeps = 5;

		parmRef = getParmRef("indep6");
		if (parmRef == null || parmRef.timeSeries == null
		 || parmRef.timeSeries.getTimeSeriesIdentifier() == null)
		{
			if (!indep6_MISSING.equalsIgnoreCase("ignore"))
				throw new DbCompException("No time series mapped to indep6");
			
			return specId;
		}
		tsid = parmRef.timeSeries.getTimeSeriesIdentifier();
		specId = specId + "," + tsid.getDataType().getCode();
		indep6Values = new ArrayList<Double>();
		numIndeps = 6;

		parmRef = getParmRef("indep7");
		if (parmRef == null || parmRef.timeSeries == null
		 || parmRef.timeSeries.getTimeSeriesIdentifier() == null)
		{
			if (!indep7_MISSING.equalsIgnoreCase("ignore"))
				throw new DbCompException("No time series mapped to indep7");
			
			return specId;
		}
		tsid = parmRef.timeSeries.getTimeSeriesIdentifier();
		specId = specId + "," + tsid.getDataType().getCode();
		indep7Values = new ArrayList<Double>();
		numIndeps = 7;

		parmRef = getParmRef("indep8");
		if (parmRef == null || parmRef.timeSeries == null
		 || parmRef.timeSeries.getTimeSeriesIdentifier() == null)
		{
			if (!indep8_MISSING.equalsIgnoreCase("ignore"))
				throw new DbCompException("No time series mapped to indep8");
			
			return specId;
		}
		tsid = parmRef.timeSeries.getTimeSeriesIdentifier();
		specId = specId + "," + tsid.getDataType().getCode();
		indep8Values = new ArrayList<Double>();
		numIndeps = 8;

		parmRef = getParmRef("indep9");
		if (parmRef == null || parmRef.timeSeries == null
		 || parmRef.timeSeries.getTimeSeriesIdentifier() == null)
		{
			if (!indep9_MISSING.equalsIgnoreCase("ignore"))
				throw new DbCompException("No time series mapped to indep9");
			
			return specId;
		}
		tsid = parmRef.timeSeries.getTimeSeriesIdentifier();
		specId = specId + "," + tsid.getDataType().getCode();
		indep9Values = new ArrayList<Double>();
		numIndeps = 9;

		return specId;
	}
//AW:LOCALVARS_END

//AW:OUTPUTS
	public NamedVariable dep = new NamedVariable("dep", 0);
	String _outputNames[] = { "dep" };
//AW:OUTPUTS_END

//AW:PROPERTIES
	public String templateVersion = "USGS-EXSA";
	public String specVersion = "Production";
	public String indep1_MISSING = "fail";
	public String indep2_MISSING = "ignore";
	public String indep3_MISSING = "ignore";
	public String indep4_MISSING = "ignore";
	public String indep5_MISSING = "ignore";
	public String indep6_MISSING = "ignore";
	public String indep7_MISSING = "ignore";
	public String indep8_MISSING = "ignore";
	public String indep9_MISSING = "ignore";

	public String _propertyNames[] = { "templateVersion", "specVersion",
		"indep1_MISSING", "indep2_MISSING", "indep3_MISSING", "indep4_MISSING", "indep5_MISSING",
		"indep6_MISSING", "indep7_MISSING", "indep8_MISSING", "indep9_MISSING" };
//AW:PROPERTIES_END

	// Allow javac to generate a no-args constructor.

	/**
	 * Algorithm-specific initialization provided by the subclass.
	 */
	protected void initAWAlgorithm( )
		throws DbCompException
	{
//AW:INIT
		_awAlgoType = AWAlgoType.TIME_SLICE;
//AW:INIT_END

//AW:USERINIT
//AW:USERINIT_END
	}
	
	/**
	 * This method is called once before iterating all time slices.
	 */
	protected void beforeTimeSlices()
		throws DbCompException
	{
//AW:BEFORE_TIMESLICES
		// Get parm refs for indep and dep
		String specId = buildIndepSpec();
		
		ParmRef depParmRef = getParmRef("dep");
		specId = specId + ";" + depParmRef.compParm.getDataType().getCode() + "."
			+ templateVersion + "." + specVersion;

		// Retrieve the RatingSet object
		try
		{
			CwmsRatingDao crd = new CwmsRatingDao((CwmsTimeSeriesDb)tsdb);
			ratingSet = crd.getRatingSet(specId);
		}
		catch (RatingException ex)
		{
			throw new DbCompException("Cannot read rating for '" + specId + "': " + ex);
		}

		indepTimes.clear();
		indep1Values.clear();
		if (indep2Values != null)
			indep2Values.clear();
		if (indep3Values != null)
			indep3Values.clear();
		if (indep4Values != null)
			indep4Values.clear();
		if (indep5Values != null)
			indep5Values.clear();
		if (indep6Values != null)
			indep6Values.clear();
		if (indep7Values != null)
			indep7Values.clear();
		if (indep8Values != null)
			indep8Values.clear();
		if (indep9Values != null)
			indep9Values.clear();
//AW:BEFORE_TIMESLICES_END
	}

	/**
	 * Do the algorithm for a single time slice.
	 * AW will fill in user-supplied code here.
	 * Base class will set inputs prior to calling this method.
	 * User code should call one of the setOutput methods for a time-slice
	 * output variable.
	 *
	 * @throw DbCompException (or subclass thereof) if execution of this
	 *        algorithm is to be aborted.
	 */
	protected void doAWTimeSlice()
		throws DbCompException
	{
//AW:TIMESLICE
		if (ratingSet == null)
			throw new DbCompException("No rating set!");
		
		// If any non-ignored params are missing in this time-slice, skip it.
		if ((isMissing("indep1") && indep1_MISSING.equalsIgnoreCase("fail"))
		 || (numIndeps >= 2 && isMissing("indep2") && indep2_MISSING.equalsIgnoreCase("fail"))
		 || (numIndeps >= 3 && isMissing("indep3") && indep3_MISSING.equalsIgnoreCase("fail"))
		 || (numIndeps >= 4 && isMissing("indep4") && indep4_MISSING.equalsIgnoreCase("fail"))
		 || (numIndeps >= 5 && isMissing("indep5") && indep5_MISSING.equalsIgnoreCase("fail"))
		 || (numIndeps >= 6 && isMissing("indep6") && indep6_MISSING.equalsIgnoreCase("fail"))
		 || (numIndeps >= 7 && isMissing("indep7") && indep7_MISSING.equalsIgnoreCase("fail"))
		 || (numIndeps >= 8 && isMissing("indep8") && indep8_MISSING.equalsIgnoreCase("fail"))
		 || (numIndeps >= 9 && isMissing("indep9") && indep9_MISSING.equalsIgnoreCase("fail")))
			return;

		double valueSet[] = new double[numIndeps];
		valueSet[0] = indep1;
		if (numIndeps >= 2) valueSet[1] = indep2;
		if (numIndeps >= 3) valueSet[2] = indep3;
		if (numIndeps >= 4) valueSet[3] = indep4;
		if (numIndeps >= 5) valueSet[4] = indep5;
		if (numIndeps >= 6) valueSet[5] = indep6;
		if (numIndeps >= 7) valueSet[6] = indep7;
		if (numIndeps >= 8) valueSet[7] = indep8;
		if (numIndeps >= 9) valueSet[8] = indep9;
		try
		{
			setOutput(dep, ratingSet.rateOne(valueSet, _timeSliceBaseTime.getTime()));
		}
		catch (RatingException ex)
		{
			warning("Rating failure: " + ex);
		}
//AW:TIMESLICE_END
	}

	/**
	 * This method is called once after iterating all time slices.
	 */
	protected void afterTimeSlices()
	{
//AW:AFTER_TIMESLICES
//AW:AFTER_TIMESLICES_END
	}

	/**
	 * Required method returns a list of all input time series names.
	 */
	public String[] getInputNames()
	{
		return _inputNames;
	}

	/**
	 * Required method returns a list of all output time series names.
	 */
	public String[] getOutputNames()
	{
		return _outputNames;
	}

	/**
	 * Required method returns a list of properties that have meaning to
	 * this algorithm.
	 */
	public String[] getPropertyNames()
	{
		return _propertyNames;
	}
}