/*
*  $Id$
*/
package decodes.consumer;

import java.util.Iterator;
import java.util.Properties;
import java.util.Date;
import java.util.Calendar;
import java.text.SimpleDateFormat;

import ilex.var.TimedVariable;
import ilex.var.IFlags;

import decodes.db.*;
import decodes.decoder.DecodedMessage;
import decodes.decoder.TimeSeries;
import decodes.decoder.Sensor;
import decodes.datasource.RawMessage;
import decodes.datasource.UnknownPlatformException;
import decodes.util.PropertySpec;

/**
  This class formats decoded data in the same way that EMIT did when
  ORACLE output was selected.
*/
public class EmitOracleFormatter extends OutputFormatter
{
	/** Column-delimiter specified by 'delimiter' property */
	private String delimiter;

	/** Used to format time stamps. */
	private SimpleDateFormat emitDateFormat;
	
	private PropertySpec propSpecs[] = 
	{		
		new PropertySpec("delimiter", PropertySpec.STRING,
			"Used between columns (default=space)")
	};

	/** default constructor */
	protected EmitOracleFormatter()
	{
		super();
		delimiter = " ";
		emitDateFormat = new SimpleDateFormat("yyDDD/HH:mm:ss");
	}

	/**
	  Initializes the Formatter. This method is called from the static
	  makeOutputFormatter method in this class. The RoutingSpec does not
	  need to call it explicitely.
	  @param type the type of this output formatter.
	  @param tz the time zone as specified in the routing spec.
	  @param presGrp The presentation group to handle rounding & EU conversions.
	  @param rsProps the routing-spec properties.
	*/
	protected void init(String type, java.util.TimeZone tz,
		PresentationGroup presGrp, Properties rsProps)
		throws OutputFormatterException
	{
		String d = (String)rsProps.get("delimiter");
		if (d != null)
			delimiter = d;
		Calendar cal = Calendar.getInstance(tz);
		emitDateFormat.setCalendar(cal);
	}

	/** Does nothing. */
	public void shutdown()
	{
	}

	/**
	  Writes the passed DecodedMessage to the passed consumer, using
	  a concrete format.
	  @param msg The message to output.
	  @param consumer The DataConsumer to output to.
	  @throws OutputFormatterException if there was a problem formatting data.
	  @throws DataConsumerException, passed through from consumer methods.
	*/
	public void writeMessage(DecodedMessage msg, DataConsumer consumer)
		throws DataConsumerException, OutputFormatterException
	{
		consumer.startMessage(msg);

		StringBuffer sb = new StringBuffer();
		RawMessage rawmsg = msg.getRawMessage();

		TransportMedium tm;
		Platform platform;

		try
		{
			tm = rawmsg.getTransportMedium();
			platform = rawmsg.getPlatform();
		}
		catch(UnknownPlatformException e)
		{
			throw new OutputFormatterException(e.toString());
		}

		String dcpId = tm.getMediumId();

		char platformType = 'I';
		if (tm.getMediumType().equalsIgnoreCase(Constants.medium_GoesRD))
			platformType = 'R';

		String platformSiteName = platform.getSiteName(false);

		for(Iterator it = msg.getAllTimeSeries(); it.hasNext(); )
		{
			TimeSeries ts = (TimeSeries)it.next();
			Sensor sensor = ts.getSensor();

			String platformName = sensor.getSensorSiteName();
			if (platformName == null)
				platformName = platformSiteName;

			EngineeringUnit eu = ts.getEU();

			DataType dt = sensor.getDataType(Constants.datatype_EPA);
			String usgsCode = dt != null ? dt.getCode() : "0";

			dt = sensor.getDataType(Constants.datatype_SHEF);
			String shefCode = dt != null ? dt.getCode() : "XX";

			String sensorNum = "" + ts.getSensorNumber();
			String sensorName = sensor.getName();

			String recordingInt = "" + sensor.getRecordingInterval();

			int sz = ts.size();
			for(int i=0; i<sz; i++)
			{
				TimedVariable tv = ts.sampleAt(i);
				if ((tv.getFlags() & (IFlags.IS_MISSING | IFlags.IS_ERROR)) != 0)
					continue;

				sb.setLength(0);
				sb.append(dcpId);
				for(int j=sb.length(); j<8; j++)
					sb.append(' ');

				sb.append(delimiter);
				sb.append(shefCode);

				sb.append(delimiter);
				sb.append(sensorNum);
				for(int j=sensorNum.length(); j<4; j++)
					sb.append(' ');
			
				sb.append(delimiter);
				sb.append(emitDateFormat.format(tv.getTime()));

				sb.append(delimiter);
				String s = ts.formattedSampleAt(i);
				sb.append(s);
				for(int j=s.length(); j<10; j++)
					sb.append(' ');
			
				sb.append(delimiter);
				sb.append(platformType);

				sb.append(delimiter);
				sb.append(eu.abbr);

				consumer.println(sb.toString());
			}
		}
		consumer.println("ZZZZ");
		consumer.endMessage();
	}
	
	@Override
	public PropertySpec[] getSupportedProps()
	{
		return propSpecs;
	}

}
