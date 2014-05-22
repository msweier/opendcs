/*
*  $Id$
*/
package lrgs.common;

import java.util.Date;
import java.util.Calendar;
import java.util.TimeZone;

import ilex.util.ArrayUtil;
import ilex.util.ByteUtil;
import ilex.util.TwoDigitYear;
import ilex.util.Logger;
import decodes.util.ResourceFactory;

/**
Data Structure that holds a single DCP Message
*/
public class DcpMsg
{
	/**
	  The DCP message data, including header, is stored as an
	  array of bytes.
	*/
	private byte[] data;
	
	/** The flag bits */
	public int flagbits;

	/** The DROT (LRGS) time stamp */
	private Date localRecvTime;

	/** (legacy) file name constructed from DCP address & sequence num */
	private String seqFileName;

	/** DOMSAT (or other) sequence number for this message (-1 if unknown) */
	private int sequenceNum;

	public byte mergeFilterCode;

	/** Baud rate if known, 0 = unknown */
	private int baud;

	/** Time stamp (msec) of carrier start (0 = unknown) */
	private Date carrierStart;

	/** Time stamp (msec) of carrier stop (0 = unknown) */
	private Date carrierStop;

	/** Time stamp (msec) of DOMSAT Receipt time (0 = unknown) */
	private Date domsatTime;

	/** The database ID of the data source from whence this message came */
	private int dataSourceId;
	
	/** GOES time stamp from header, or SBD session time. */
	private Date xmitTime;
	
	/** Iridium SBD Mobile Terminated Msg Sequence Number */
	private int mtmsm;

	/** Iridium SBD CDR Reference Number */
	private long cdrReference;
	
	/** For GOES: DCP Address, For Iridium SBD, International Mobile Equip ID */
	private DcpAddress dcpAddress = null;
	
	/** For Iridium SBD, store the session status. */
	private int sessionStatus = 0;
	
	/** reserved for future use. */
	public byte reserved[];

	/** Max length that can be expressed in the 5-digit domsat header. */
	public static final int MAX_DATA_LENGTH = 99999;
	
	/** transient storage for DRGS interface. Original address is NOT saved. */
	private DcpAddress origAddress = null;
	
	/** transient storage for battery voltage, used by DCP Monitor. */
	private double battVolt = 0.0;
	
	/** Failure code for non-GOES messages */
	private char failureCode = (char)0;
	
	private Object extraMeasurements = null;

	// Constructors ===============================================

	/** Allocate an empty DCP message */
	public DcpMsg()
	{
		data = null;
		flagbits = 0;
		localRecvTime = DcpMsgIndex.zeroDate;
		sequenceNum = -1;
		mergeFilterCode = (byte)0;
		baud = 0;
		setCarrierStart(null);
		setCarrierStop(null);
		setDomsatTime(null);
		setDataSourceId(-1);
		reserved = new byte[32];
		setXmitTime(null);
		setMtmsm(0);
		setCdrReference(0L);
	}

	/** 
	  Use the passed byte array to allocate a new DCP Message.
	  @param data the data bytes
	  @param size number of data bytes in this message
	*/
	public DcpMsg(byte data[], int size)
	{
		this();
		set(data, size);
	}

	/** 
	  Use the passed byte array to allocate a new DCP Message 
	  @param data the data bytes
	  @param offset in data where this message starts
	  @param size number of data bytes in this message
	*/
	public DcpMsg(byte data[], int size, int offset)
	{
		this();
		set(data, size, offset);
	}
	
	public DcpMsg(DcpAddress dcpAddress, int flags, byte data[], int size,
		int offset)
	{
		this();
		this.flagbits = flags;
		this.dcpAddress = dcpAddress;
		set(data, size, offset);
	}

	/**
	  @return the entire length of the data, including header.
	*/
	public int length() 
	{
		return data != null ? data.length : 0;
	}

	/**
	 * Sets the local receive time.
	 * @param t the time value as a Unix time_t.
	 */
	public void setLocalReceiveTime(Date t)
	{
		localRecvTime = t;
	}

	/** @return the local receive time as a unix time_t. */
	public Date getLocalReceiveTime() { return localRecvTime; }

	/** 
	 * @return the sequence number associated with this message, or -1 if none.
	 */
	public int getSequenceNum()
	{
		return sequenceNum;
	}

	/**
	 * Sets the seqnuence number to be associated with this msg.
	 * @param sn the sequence num.
	 */
	public void setSequenceNum(int sn)
	{
		sequenceNum = sn;
	}

	/**
	  Allocate a new data array to the specified size. It will be filled
	  with null bytes initially.
	  @param size number of bytes to reserve
	*/
	public void reserve(int size)
	{
		data = new byte[size];
		for(int i=0; i<size; i++)
			data[i] = (byte)0;
	}

	/**
	  Allocate a new data field and populate it with a copy of the
	  passed byte array.
	  @param data the data bytes
	  @param size number of data bytes in this message
	*/
	public void set(byte data[], int size)
	{
		set(data, size, 0);
	}

	/**
	  Allocate a new data field and populate it with a copy of the
	  passed byte array.
	  @param data the data bytes
	  @param offset in data where this message starts
	  @param size number of data bytes in this message
	*/
	public void set(byte data[], int size, int offset)
	{
		if (isGoesMessage() 
		 && (size > MAX_DATA_LENGTH || size < 0))
		{
			Logger.instance().warning("Cannot set DcpMsg data, invalid size="
				+ size + ", attempting to parse length from header.");

			byte ml[] = ArrayUtil.getField(data, offset + 32, 5);
			if (ml == null)
			{
				Logger.instance().warning("Parse failed setting empty msg.");
				size = 0;
			}
			try 
			{
				size = 37 + Integer.parseInt(new String(ml));
				Logger.instance().warning("Parsed msg length = " + size);
			}
			catch(NumberFormatException ex)
			{
				Logger.instance().warning("Parse failed setting empty msg.");
				size = 0;
			}
		}
		byte[] buf  = new byte[size];
		for(int i = 0; i<size; i++)
			buf[i] = data[offset+i];
		setData(buf);
	}

	public void setData(byte[] buf)
	{
		this.data = buf;
		if (DcpMsgFlag.isGOES(flagbits))
		{
			setXmitTime(getDapsTime());
			setDcpAddress(this.getGoesDcpAddress());
		}
	}

	/**
	  Return a subset of the data as a new byte array. Returns
	  null if either of the indices are outside the array bounds.
	  @return a subset of the data as a new byte array, or
	  null if either of the indices are outside the array bounds.
	*/
	public byte[] getField(int start, int length) 
	{
		byte ret[] = ArrayUtil.getField(data, start, length);
		if (ret == null)
		{
			Logger.instance().warning("Invalid msg length=" + data.length
				+ ", field=" + start + "..." + (start+length));
		}
		return ret;
	}

	/**
	  @return address of DCP that sent this message.
	*/
	public DcpAddress getGoesDcpAddress() 
	{
		byte addrfield[] = getField(IDX_DCP_ADDR, 8);
		if (addrfield == null)
			throw new NumberFormatException("No data");
		return new DcpAddress(new String(addrfield));
	}

	/**
	  @return date/time that message was received by DAPS.
	*/
	public Date getDapsTime()
	{
		if (!DcpMsgFlag.isGOES(this.flagbits))
			return this.xmitTime;

		try
		{
			Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
			cal.clear();

			byte field[] = getField(IDX_YEAR, 2);
			if (field == null)
				return new Date(0);

			cal.set(Calendar.YEAR, TwoDigitYear.getYear(new String(field)));

			field = getField(IDX_DAY, 3);
			if (field == null)
				return new Date(0);
			int i = Integer.parseInt(new String(field));
			cal.set(Calendar.DAY_OF_YEAR, i);

			field = getField(IDX_HOUR, 2);
			if (field == null)
				return new Date(0);
			i = Integer.parseInt(new String(field));
			cal.set(Calendar.HOUR_OF_DAY, i);

			field = getField(IDX_MIN, 2);
			if (field == null)
				return new Date(0);
			i = Integer.parseInt(new String(field));
			cal.set(Calendar.MINUTE, i);

			field = getField(IDX_SEC, 2);
			if (field == null)
				return new Date(0);
			i = Integer.parseInt(new String(field));
			cal.set(Calendar.SECOND, i);

			return cal.getTime();
		}
		catch(Exception ex)
		{
			// Note NumberFormatException can happen if garbage in the
			// date fields.
			// If this happens, just return the current time.
			return new Date();
		}
	}

	/**
	  @return failure code contained in this message.
	*/
	public char getFailureCode()
	{
		if (!isGoesMessage())
			return failureCode;
				
		byte field[] = getField(IDX_FAILCODE, 1);
		if (field == null)
			return '-';
		return (char)field[0];
	}

	/**
	  @return true if this is a DAPS-generated status message.
	*/
	public boolean isDapsStatusMsg()
	{
		char c = getFailureCode();
		return isGoesMessage() && !(c == 'G' || c == '?');
	}
	
	/** @return true if this is a GOES message from any source */
	public boolean isGoesMessage()
	{
		return DcpMsgFlag.isGOES(flagbits);
	}

	/**
	  @return signal strength as an integer.
	*/
	public int getSignalStrength()
	{
		byte field[] = getField(IDX_SIGSTRENGTH, 2);
		if (field == null)
			return 0;
		try { return Integer.parseInt(new String(field)); }
		catch(NumberFormatException ex)
		{
			return 0;
		}
	}

	/**
	  @return the frequence offset in increments of 50 Hz.
	*/
	public int getFrequencyOffset()
	{
		byte field[] = getField(IDX_FREQOFFSET, 2);
		if (field == null)
			return 0;
		char c = (char)field[1];
		int i = ByteUtil.fromHexChar(c);
		return (char)field[0] == '-' ? -i : i;
	}

	/**
	  @return 'N' for Normal, 'L' for Low, 'H' for High, or 'U' for unknown.
	*/
	public char getModulationIndex()
	{
		byte field[] = getField(IDX_MODINDEX, 1);
		if (field == null)
			return 'U';
		return (char)field[0];
	}

	/**
	  @return 'N' for normal, 'F' for fair, 'P' for poor, or 'U' for unknown.
	*/
	public char getDataQuality()
	{
		byte field[] = getField(IDX_DATAQUALITY, 1);
		if (field == null)
			return 'U';
		return (char)field[0];
	}

	/**
	  @return GOES Channel number in range 1...266, 0 if unknown.
	*/
	public int getGoesChannel()
	{
		byte field[] = getField(IDX_GOESCHANNEL, 3);
		if (field == null)
			return 0;
		for(int i=0; i<field.length; i++)
			if (field[i] == (byte)' ')
				field[i] = (byte)'0';

		try { return Integer.parseInt(new String(field)); }
		catch(NumberFormatException ex)
		{
			return 0;
		}
	}

	/**
	  @return 'E' for GOES East, 'W' for GOES West, 'U' for unknown.
	*/
	public char getGoesSpacecraft()
	{
		byte field[] = getField(IDX_GOES_SC, 1);
		if (field == null)
			return 'U';
		return (char)field[0];
	}

	/**
	  Uplink status is represented in the message by 2 hex digits.
	  This is also used to store a 2-char DRGS source code.
	  @return uplink status code as an integer.
	*/
	public String getDrgsCode()
	{
		byte field[] = getField(DRGS_CODE, 2);
		if (field == null)
			return "xx";
		return new String(field);
	}

	/**
	  @return the length of the DCP data, as reported in the message, or 0
	   if length cannot be parsed.
	*/
	public int getDcpDataLength()
	{
		if (!isGoesMessage())
			return this.length();
		
		byte field[] = getField(IDX_DATALENGTH, 5);
		if (field == null)
			return 0;
		try { return Integer.parseInt(new String(field)); }
		catch(NumberFormatException ex)
		{
			return 0;
		}
	}

	/**
	  @return the message-proper. That is, the data actually sent by 
	  the DCP.  Note that due to possible transmission errors, the 
	  length may not be equal to the value returned by getDcpDataLength.
	*/
	public byte[] getDcpData()
	{
		if (data.length <= IDX_DATA)
			return new byte[0];

		return getField(IDX_DATA, data.length-IDX_DATA);
	}

	// Constants for accessing fields. ===============
	public static final int IDX_DCP_ADDR      = 0;
	public static final int IDX_YEAR          = 8;
	public static final int IDX_DAY           = 10;
	public static final int IDX_HOUR          = 13;
	public static final int IDX_MIN           = 15;
	public static final int IDX_SEC           = 17;
	public static final int IDX_FAILCODE      = 19;
	public static final int IDX_SIGSTRENGTH   = 20;
	public static final int IDX_FREQOFFSET    = 22;
	public static final int IDX_MODINDEX      = 24;
	public static final int IDX_DATAQUALITY   = 25;
	public static final int IDX_GOESCHANNEL   = 26;
	public static final int IDX_GOES_SC       = 29;
	public static final int DRGS_CODE    = 30;
	public static final int IDX_DATALENGTH    = 32;
	public static final int IDX_DATA          = 37;

	public static final int DCP_MSG_MIN_LENGTH = 37;

	/**
	  @return entire DCP message as a string.
	*/
	public String toString()
	{
		return new String(data);
	}

	/**
	  @return the 37-char DOMSAT header portion of the message.
	*/
	public String getHeader()
	{
		if (data.length < 37)
			return new String(data);
		else
			return new String(data, 0, 37);
	}

	/**
	  Make a quasi-unique temporary file suitable for storing this message.
	  @param sequence sequence number to make the filename unique
	  @return filename
	*/
	public String makeFileName(int sequence)
	{
		
		return getDcpAddress().toString() + "-" + sequence;
/*
		try
		{
			StringBuffer sb = new StringBuffer(getDcpAddress().toString());
			sb.append('.');
			int x = sequence % 65536;
			sb.append((char)((int)'a' + (x/676)));
			sb.append((char)((int)'a' + ((x%676)/26)));
			sb.append((char)((int)'a' + (x%26)));
			return new String(sb);
		}
		catch (Exception e)
		{
			return "tmpdcpfile";
		}
*/
	}

	public void setBaud(int b)
	{
		baud = b;
		switch(b)
		{
		case 100: flagbits |= DcpMsgFlag.BAUD_100; break;
		case 300: flagbits |= DcpMsgFlag.BAUD_300; break;
		case 1200: flagbits |= DcpMsgFlag.BAUD_1200; break;
		}
	}

	public int getBaud() { return baud; }

	/**
     * @param seqFileName the seqFileName to set
     */
    public void setSeqFileName(String seqFileName)
    {
	    this.seqFileName = seqFileName;
    }

	/**
     * @return the seqFileName
     */
    public String getSeqFileName()
    {
	    return seqFileName;
    }

	/**
     * @param carrierStart the carrierStart to set
     */
    public void setCarrierStart(Date carrierStart)
    {
	    this.carrierStart = carrierStart;
    }

	/**
     * @return the carrierStart
     */
    public Date getCarrierStart()
    {
	    return carrierStart;
    }

	/**
     * @param carrierStop the carrierStop to set
     */
    public void setCarrierStop(Date carrierStop)
    {
	    this.carrierStop = carrierStop;
    }

	/**
     * @return the carrierStop
     */
    public Date getCarrierStop()
    {
	    return carrierStop;
    }

	/**
     * @param domsatTime the domsatTime to set
     */
    public void setDomsatTime(Date domsatTime)
    {
	    this.domsatTime = domsatTime;
    }

	/**
     * @return the domsatTime
     */
    public Date getDomsatTime()
    {
	    return domsatTime;
    }

	/**
     * @param dataSourceId the dataSourceId to set
     */
    public void setDataSourceId(int dataSourceId)
    {
	    this.dataSourceId = dataSourceId;
    }

	/**
     * @return the dataSourceId
     */
    public int getDataSourceId()
    {
	    return dataSourceId;
    }

	/**
     * @param xmitTime the xmitTime to set
     */
    public void setXmitTime(Date xmitTime)
    {
	    this.xmitTime = xmitTime;
    }

	/**
     * @return the xmitTime
     */
    public Date getXmitTime()
    {
	    return xmitTime;
    }

	/**
     * @param mtmsm the mtmsm to set
     */
    public void setMtmsm(int mtmsm)
    {
	    this.mtmsm = mtmsm;
    }

	/**
     * @return the mtmsm
     */
    public int getMtmsm()
    {
	    return mtmsm;
    }

	/**
     * @param cdrReference the cdrReference to set
     */
    public void setCdrReference(long cdrReference)
    {
	    this.cdrReference = cdrReference;
    }

	/**
     * @return the cdrReference
     */
    public long getCdrReference()
    {
	    return cdrReference;
    }

	/**
     * @param platformId the platformId to set
     */
    public void setDcpAddress(DcpAddress dcpAddress)
    {
	    this.dcpAddress = dcpAddress;
    }

	/**
     * @return the platformId
     */
    public DcpAddress getDcpAddress()
    {
	    return dcpAddress;
    }
    
	public byte[] getData() { return data; }

	/**
     * @param origAddress the origAddress to set
     */
    public void setOrigAddress(DcpAddress origAddress)
    {
	    this.origAddress = origAddress;
    }

	/**
     * @return the origAddress
     */
    public DcpAddress getOrigAddress()
    {
	    return origAddress;
    }

	/**
     * @param battVolt the battVolt to set
     */
    public void setBattVolt(double battVolt)
    {
	    this.battVolt = battVolt;
    }

	/**
     * @return the battVolt
     */
    public double getBattVolt()
    {
	    return battVolt;
    }

	/**
     * @return the Iridium SBD sessionStatus
     */
    public int getSessionStatus()
    {
    	return sessionStatus;
    }

	/**
     * @param sessionStatus the Iridium SBD sessionStatus to set
     */
    public void setSessionStatus(int sessionStatus)
    {
    	this.sessionStatus = sessionStatus;
    	if (DcpMsgFlag.isIridium(flagbits))
    		setFailureCode(sessionStatus <= 2 ? 'G' : '?');

    }
    
    public void setFailureCode(char fc)
    {
    	this.failureCode = fc;
    }

	/**
     * @return the flagbits
     */
    public int getFlagbits()
    {
    	return flagbits;
    }

	/**
     * @param flagbits the flagbits to set
     */
    public void setFlagbits(int flagbits)
    {
    	this.flagbits = flagbits;
    	if (DcpMsgFlag.isNetDcp(flagbits))
    		setFailureCode('G');
    }

	public Object getExtraMeasurements()
	{
		return extraMeasurements;
	}

	public void setExtraMeasurements(Object extraMeasurements)
	{
		this.extraMeasurements = extraMeasurements;
	}

}