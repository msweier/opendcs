/*
 * $Id$
 * 
 * Open Source Software
 * 
 * $Log$
 * Revision 1.15  2013/04/22 16:13:32  mmaloney
 * Adjust visibility to implement CwmsPlatformListIO subclass.
 *
 * Revision 1.14  2013/04/16 19:22:26  mmaloney
 * column mask on inserts required for VPD
 *
 * Revision 1.13  2013/03/21 18:27:39  mmaloney
 * DbKey Implementation
 *
 */
package decodes.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Vector;

import opendcs.dai.PlatformStatusDAI;
import opendcs.dai.PropertiesDAI;
import opendcs.dao.PropertiesSqlDao;

import ilex.util.Logger;
import ilex.util.PropertiesUtil;
import ilex.util.TextUtil;

import decodes.db.Constants;
import decodes.db.DatabaseException;
import decodes.db.Platform;
import decodes.db.PlatformConfig;
import decodes.db.PlatformList;
import decodes.db.PlatformSensor;
import decodes.db.SiteName;
import decodes.db.TransportMedium;
import decodes.db.Site;
import decodes.tsdb.DbIoException;

/**
 * This handles the I/O of the PlatformList object and some of its
 * kids to/from the SQL database.
 * When reading, this reads the Platform and TransportMedium tables.
 * Note, though, that only the MediumType and MediumId fields of the
 * TransportMedium table are used.
 */
public class PlatformListIO extends SqlDbObjIo
{
	/** Transient reference to the PlatformList that we're working on. */
	protected PlatformList _pList;

	/** Transient reference to the PlatformList that we're working on. */
	protected PlatformList _platList;
	/**
	* This is a reference to the ConfigListIO object for this SQL database.
	* This is passed in from the SqlDatabaseIO object when this PlatformListIO
	* object is created.  It's used to retrieve the
	* PlatformConfig objects corresponding to particular ID numbers.
	*/
	protected ConfigListIO _configListIO;

	/**
	* This is a reference to the EquipmentModelListIO object for this
	* database.  This is used to retrieve EquipmentModel objects by their
	* ID numbers.
	*/
	protected EquipmentModelListIO _equipmentModelListIO;

	/**
	* This is a reference to the DecodesScriptIO object for this
	* database.  This is used to retrieve DecodesScript objects by their
	* ID numbers.
	*/
	protected DecodesScriptIO _decodesScriptIO;

	private String coltpl;

	/**
	* Constructor.
	* @param dbio the SqlDatabaseIO to which this IO object belongs
	* @param configListIO used to access platform configs
	* @param emlIO used to access EquipmentModel records
	* @param dsIO used to access Decodes Scripts
	*/
	public PlatformListIO(SqlDatabaseIO dbio,
						  ConfigListIO configListIO,
						  EquipmentModelListIO emlIO,
						  DecodesScriptIO dsIO)
	{
		super(dbio);

		_configListIO = configListIO;
		_equipmentModelListIO = emlIO;
		_decodesScriptIO = dsIO;

	}

	private String getColTpl()
	{
		if (coltpl == null)
		{
			// column template
			coltpl = "id, agency, isProduction, siteId, configId"
				+ ", description, lastModifyTime, expiration";
			if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7)
				coltpl = coltpl + ", platformDesignator";
//Logger.instance().debug3("dbversion = " + getDatabaseVersion() + ", coltpl = '" + coltpl + "'");
		}
		return coltpl;
	}

	/**
	* Read the PlatformList.
	* This reads partial data from the Platform and TransportMedium tables.
	* This corresponds to reading the platform/PlatformList.xml file of
	* the XML database.
	* The partial data of the Platform table are the following fields:
	* <ul>
	*   <li>platformId</li>
	*   <li>description</li>
	*   <li>agency</li>
	*   <li>expiration</li>
	*   <li>configName</li>
	*   <li>site</li>
	*   <li>transportMedia - partial data</li>
	*   <li>isReadComplete - should be false</li>
	* </ul>
	* The partial data of the TransportMedium table are the MediumType and
	* MediumId fields.
	* @param platformList the PlatformList object to populate
	*/
	public void read(PlatformList platformList)
		throws SQLException, DatabaseException
	{
		debug1("Reading PlatformList...");

		_pList = platformList;

		Statement stmt = createStatement();
		String q = 
			(getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7) ?
				("SELECT ID, Agency, IsProduction, " +
				 "SiteId, ConfigId, Description, " +
				 "LastModifyTime, Expiration, platformDesignator " +
				 "FROM Platform")
			:
				("SELECT ID, Agency, IsProduction, " +
				 "SiteId, ConfigId, Description, " +
				 "LastModifyTime, Expiration " +
				 "FROM Platform");

		ResultSet rs = stmt.executeQuery(q);

		if (rs != null) {
			while (rs.next()) 
			{
				DbKey platformId = DbKey.createDbKey(rs, 1);

				// MJM 20041027 Check to see if this ID is already in the
				// cached platform list and ignore if so. That way, I can
				// periodically refresh the platform list to get any newly
				// created platforms after the start of the routing spec.
				// Refreshing will not affect previously read/used platforms.
				Platform p = _pList.getById(platformId);
				if (p != null)
					continue;

				p = new Platform(platformId);
				_pList.add(p);

				p.agency = rs.getString(2);

				DbKey siteId = DbKey.createDbKey(rs, 4);
				if (!rs.wasNull()) {
					p.site = p.getDatabase().siteList.getSiteById(siteId);
				}

				DbKey configId = DbKey.createDbKey(rs, 5);
				if (!rs.wasNull()) 
				{
					PlatformConfig pc = 
						platformList.getDatabase().platformConfigList.getById(
							configId);
					if (pc == null)
{
Logger.instance().debug1("config(" + configId + ") not in list, will read...");
						pc = _configListIO.getConfig(configId);
}
					p.setConfigName(pc.configName);
					p.setConfig(pc);
				}

				String desc = rs.getString(6);
				if (!rs.wasNull()) 
					p.setDescription(desc);

				p.lastModifyTime = getTimeStamp(rs, 7, null);

				p.expiration = getTimeStamp(rs, 8, p.expiration);

				if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7)
					p.setPlatformDesignator(rs.getString(9));

				// Now get the TransportMediums for this platform
//				readTransportMediaPartial(p);
			}
		}
		stmt.close();
		readAllTransportMediaPartial(platformList);
	}

	
	/**
	* Read the PlatformList.
	* This reads  data from the Platform based on the contact/transport medium	
	* @param platformList the PlatformList object to populate
	*/
	public void readPlatforms(PlatformList platformList,ArrayList<String> contMedium)
		throws SQLException, DatabaseException
	{
		debug1("Reading PlatformList based on transport medium...");

		_platList = platformList;

		for(int i=0;i<contMedium.size();i++)
		{
			Statement stmt = createStatement();
			String q = 
				(getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7) ?
					("SELECT ID, Agency, IsProduction, " +
					 "SiteId, ConfigId, Description, " +
					 "LastModifyTime, Expiration, platformDesignator " +
					 "FROM Platform, Transportmedium where platform.id = transportmedium.platformid and transportmedium.mediumtype='"+contMedium.get(i)+"'")
				:
					("SELECT ID, Agency, IsProduction, " +
					 "SiteId, ConfigId, Description, " +
					 "LastModifyTime, Expiration " +
					 "FROM Platform, Transportmedium where platform.id = transportmedium.platformid and transportmedium.mediumtype='"+contMedium.get(i)+"'");
	
					
			ResultSet rs = stmt.executeQuery(q);
	
			
			if (rs != null) {
				while (rs.next()) 
				{
					
					DbKey platformId = DbKey.createDbKey(rs, 1);
	
					
				/*	// MJM 20041027 Check to see if this ID is already in the
					// cached platform list and ignore if so. That way, I can
					// periodically refresh the platform list to get any newly
					// created platforms after the start of the routing spec.
					// Refreshing will not affect previously read/used platforms.
					Platform p = _platList.getById(platformId);
					
					System.out.println(p);
					if (p != null)
						continue;*/
	
					Platform p  = new Platform(platformId);
					_platList.addpaltform(p);
	
					p.agency = rs.getString(2);
	
					DbKey siteId = DbKey.createDbKey(rs, 4);
					if (!rs.wasNull()) {
						p.site = p.getDatabase().siteList.getSiteById(siteId);
					}
	
					DbKey configId = DbKey.createDbKey(rs, 5);
					if (!rs.wasNull()) 
					{
						PlatformConfig pc = 
							platformList.getDatabase().platformConfigList.getById(
								configId);
						if (pc == null)
{
Logger.instance().debug1("config(" + configId + ") not in list, will read...");
							pc = _configListIO.getConfig(configId);
}
						p.setConfigName(pc.configName);
						p.setConfig(pc);
					}
	
					String desc = rs.getString(6);
					if (!rs.wasNull()) 
						p.setDescription(desc);
	
					p.lastModifyTime = getTimeStamp(rs, 7, null);
	
					p.expiration = getTimeStamp(rs, 8, p.expiration);
	
					if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7)
						p.setPlatformDesignator(rs.getString(9));
	
					// Now get the TransportMediums for this platform
	//				readTransportMediaPartial(p);
				}
			}
			stmt.close();
			readAllTransportMediaPartial(platformList);
		}
	}
	
	
	protected void readAllTransportMediaPartial(PlatformList platformList)
		throws SQLException
	{
		Statement stmt = createStatement();
		String q = 
			"SELECT platformId, mediumType, mediumId, channelNum, scriptName "
			+ "from TransportMedium";
		ResultSet rs = stmt.executeQuery(q);
		while (rs != null && rs.next()) 
		{
			DbKey platId = DbKey.createDbKey(rs, 1);
			String type = rs.getString(2);
			String mediumId = rs.getString(3);
			int channelNum = rs.getInt(4);
			if (rs.wasNull()) 
				channelNum = Constants.undefinedIntKey;
			String script = rs.getString(5);
			Platform p = platformList.getById(platId);
			if (p == null)
			{
				Logger.instance().warning(
					"TM for non-existent platform id=" + platId
					+ " TM.type=" + type + ", TM.mediumId=" + mediumId);
				continue;
			}

			TransportMedium tm = p.getTransportMedium(type);
			if (tm == null)
			{
				tm = new TransportMedium(p, type, mediumId);
				p.transportMedia.add(tm);
			}
			else
				tm.setMediumId(mediumId);

			tm.channelNum = channelNum;
			tm.scriptName = script;
		}
		stmt.close();
	}

	

	/**
	* Read the information from the TransportMedium table about the
	* TransportMedium objects associated with a particular platform.
	* This then instantiates the TransportMedium object and adds it to
	* the appropriate containers.
	* This only reads partial data from the table:
	* MediumType and MediumId.  This corresponds to the data in the
	* platform/PlatformList.xml file in the XML database.
	* @param p the Platform
	*/
	public void readTransportMediaPartial(Platform p)
		throws SQLException, DatabaseException
	{
		p.transportMedia.clear();

		Statement stmt = createStatement();
		ResultSet rs = stmt.executeQuery(
			"SELECT mediumType, mediumId, channelNum " +
			"FROM TransportMedium " +
			"WHERE PlatformId = " + p.getId()
		);

		if (rs != null) {
			while (rs.next()) {
				String type = rs.getString(1);

				String mediumId = rs.getString(2);
				int channelNum = rs.getInt(3);
				if (rs.wasNull()) 
					channelNum = Constants.undefinedIntKey;

				TransportMedium tm = new TransportMedium(p, type, mediumId);
				tm.channelNum = channelNum;
				p.transportMedia.add(tm);
			}
		}
		stmt.close();
	}

	/**
	* This reads the "complete" data for the given Platform.
	* @param p the Platform
	*/
	public void readPlatform(Platform p)
		throws SQLException, DatabaseException
	{
		Statement stmt = createStatement();
		String q = "SELECT " + getColTpl() + " FROM Platform WHERE ID = " + p.getId();
		debug3("readPlatform() Executing '" + q + "'");
		ResultSet rs = stmt.executeQuery(q);

		// Can only be 1 platform with a given ID.
		if (rs != null && rs.next())
		{
			p.setAgency(rs.getString(2));

			p.isProduction = TextUtil.str2boolean(rs.getString(3));

			DbKey siteId = DbKey.createDbKey(rs, 4);
			if (!rs.wasNull()) 
			{
				// If site was previously loaded, use it.
				p.site = p.getDatabase().siteList.getSiteById(siteId);

				boolean commitAfterSelect = _dbio.commitAfterSelect;
				try
				{
					// Caller will commit after THIS method returns, so don't
					// have it commit after we read the site.
					_dbio.commitAfterSelect = false;
					
					// Else attempt to read site from database.
					if (p.site == null)
					{
						if (siteId != Constants.undefinedId)
						{
							Site site = new Site();
							site.setId(siteId);
							try 
							{
								site.read(); 
								p.getDatabase().siteList.addSite(site);
								p.site = site;
							}
							catch(DatabaseException ex)
							{
								warning("Platform with invalid site ID="
									+ siteId + ", site record left blank.");
							}
						}
					}
					else
						p.site.read();
				}
				finally
				{
					_dbio.commitAfterSelect = commitAfterSelect;
				}
			}

			DbKey configId = DbKey.createDbKey(rs, 5);
			if (!rs.wasNull()) 
			{
				// Get config out of database's list.
				PlatformConfig pc =
					p.getDatabase().platformConfigList.getById(configId);
				boolean commitAfterSelect = _dbio.commitAfterSelect;
				try
				{
					// Caller will commit after THIS method returns, so don't
					// have it commit after we read the site.
					_dbio.commitAfterSelect = false;
					
					if (pc == null)
					{
						// Not in database's list yet? Add it.
						pc = _configListIO.readConfig(configId);
						if (pc != null)
							p.getDatabase().platformConfigList.add(pc);
					}
					// Already in list. Check to see if it's current.
					else
						pc.read();
				}
				finally
				{
					_dbio.commitAfterSelect = commitAfterSelect;
				}

				if (pc != null)
				{
					p.setConfigName(pc.configName);
					p.setConfig(pc);
				}
			}

			p.setDescription(rs.getString(6));
			p.lastModifyTime = getTimeStamp(rs, 7, p.lastModifyTime);
			p.expiration = getTimeStamp(rs, 8, p.expiration);
			if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7)
				p.setPlatformDesignator(rs.getString(9));

			readTransportMediaPartial(p);
			readTransportMediaComplete(p);

			if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_6)
			{
				PropertiesDAI propsDao = _dbio.makePropertiesDAO();
				try { propsDao.readProperties("PlatformProperty", "platformID", p.getId(), 
					p.getProperties()); }
				catch (DbIoException e)
				{
					throw new DatabaseException(e.getMessage());
				}
				finally
				{
					propsDao.close();
				}
			}
		}

		stmt.close();

		readPlatformSensors(p);
	}

	/**
	* This reads the PlatformSensor and PlatformSensorProperty records
	* associated with this Platform.
	* The Platform argument must have had its ID set.
	* @param p the Platform
	*/
	public void readPlatformSensors(Platform p)
		throws DatabaseException, SQLException
	{
		Statement stmt = createStatement();
		
		String q = "SELECT platformId, sensorNumber, siteId";
		if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7)
		{
			q = q + ", dd_nu";
		}
		q = q + " FROM PlatformSensor WHERE PlatformId = " 
	       + p.getId() + " ORDER BY sensorNumber";
		
		ResultSet rs = stmt.executeQuery(q);

		if (rs != null) 
		{
			while (rs.next()) 
			{
				int sn = rs.getInt(2);
				PlatformSensor ps = new PlatformSensor(p, sn);
				p.addPlatformSensor(ps);

				DbKey siteId = DbKey.createDbKey(rs, 3);
				if (!rs.wasNull()) 
				{
					// If site was previously loaded, use it.
					ps.site = p.getDatabase().siteList.getSiteById(siteId);

					boolean commitAfterSelect = _dbio.commitAfterSelect;
					try
					{
						// Caller will commit after THIS method returns, so don't
						// have it commit after we read the site.
						_dbio.commitAfterSelect = false;

						// Else attempt to read site from database.
						if (ps.site == null)
						{
							if (siteId != Constants.undefinedId)
							{
								Site site = new Site();
								site.setId(siteId);
								try 
								{
									site.read(); 
									p.getDatabase().siteList.addSite(site);
									ps.site = site;
								}
								catch(DatabaseException ex)
								{
									warning("Platform Sensor with invalid site ID="
										+ siteId + ", site record left blank.");
								}
							}
						}
						else
							ps.site.read();
					}
					finally
					{
						_dbio.commitAfterSelect = commitAfterSelect;
					}
				}
				if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7)
				{
					int dd_nu = rs.getInt(4);
					ps.setUsgsDdno( !rs.wasNull() ? dd_nu : 0 );
				}

				readPSProps(ps, p.getId());
			}
		}
		stmt.close();
	}

	/**
	* Read the PlatformSensorProperty's associated with a PlatformSensor.
	* @param ps the PlatformSensor in which to place the properties
	* @param pid the platform ID
	*/
	public void readPSProps(PlatformSensor ps, DbKey pid)
		throws DatabaseException, SQLException
	{
		PropertiesDAI propsDao = _dbio.makePropertiesDAO();
		try { propsDao.readProperties("PlatformSensorProperty", "platformID", "SensorNumber",
			ps.platform.getId(), ps.sensorNumber, ps.getProperties()); }
		catch (DbIoException e)
		{
			throw new DatabaseException(e.getMessage());
		}
		finally
		{
			propsDao.close();
		}

		String ddno = PropertiesUtil.getIgnoreCase(ps.getProperties(), "DDNO");
		if (ddno != null)
			try 
			{
				ps.setUsgsDdno(Integer.parseInt(ddno));
				PropertiesUtil.rmIgnoreCase(ps.getProperties(), "DDNO");
			}
			catch(NumberFormatException ex) { }
	}

	/**
	* This reads the complete data of the TransportMedia associated with
	* a particular Platform.
	* @param p the Platform
	*/
	public void readTransportMediaComplete(Platform p)
		throws SQLException, DatabaseException
	{
		Vector<TransportMedium> tmv = p.transportMedia;
		for (int i = 0; i < tmv.size(); ++i)
		{
			TransportMedium tm = tmv.get(i);
			readTransportMediumComplete(tm);
		}
	}

	/**
	* This reads the complete data of a particular TranportMedium.
	* @param tm the TransportMedium
	*/
	public void readTransportMediumComplete(TransportMedium tm)
		throws DatabaseException, SQLException
	{
		String q =
			"SELECT scriptName, channelNum, assignedTime, transmitWindow, " +
			"transmitInterval, equipmentId";
		if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_6)
			q = q + ", timeAdjustment, preamble";
		if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7)
			q = q + ", timeZone";
		q = q + " FROM TransportMedium " +
			"WHERE MediumType = " + sqlReqString(tm.getMediumType()) + " AND " +
			"MediumId = '" + tm.getMediumId() + "'";
		//debug3("Executing '" + q + "'");

		Statement stmt = createStatement();
		ResultSet rs = stmt.executeQuery(q);

		if (rs != null) {
			while (rs.next()) 
			{
				tm.scriptName = rs.getString(1);

				int channelNum = rs.getInt(2);
				boolean hasChannelNum = !rs.wasNull();
				if (hasChannelNum) tm.channelNum = channelNum;

				int assignedTime = rs.getInt(3);
				boolean hasAssignedTime = !rs.wasNull();
				if (hasAssignedTime) tm.assignedTime = assignedTime;

				int transmitWindow = rs.getInt(4);
				boolean hasTransmitWindow = !rs.wasNull();
				if (hasTransmitWindow) tm.transmitWindow = transmitWindow;

				int transmitInterval = rs.getInt(5);
				boolean hasTransmitInterval = !rs.wasNull();
				if (hasTransmitInterval)
					tm.transmitInterval = transmitInterval;

				DbKey equipmentId = DbKey.createDbKey(rs, 6);
				if (!equipmentId.isNull()) 
				{
					tm.equipmentModel =
						_equipmentModelListIO.getEquipmentModel(equipmentId,
							tm.getDatabase());
				}

				if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_6)
				{
					int ta = rs.getInt(7);
					if (rs.wasNull())
						ta = 0;
					tm.setTimeAdjustment(ta);
					String str = rs.getString(8);
					if (!rs.wasNull() && str != null && str.length() > 0)
						tm.setPreamble(Character.toUpperCase(str.charAt(0)));
					if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7)
					{
						str = rs.getString(9);
						if (rs.wasNull() || str.trim().length() == 0)
							str = null;
						tm.setTimeZone(str);
					}
				}
			}
		}
		stmt.close();
	}

	/**
	* Writes a complete Platform back out to the database.  If the Platform's
	* ID is -1, then we know that this is a new Platform.
	* @param p the Platform
	*/
	public void writePlatform(Platform p)
		throws SQLException, DatabaseException
	{
		//System.out.println("	PlatformListIO.writePlatform(p)");

		if (p.idIsSet())
			update(p);
		else
		{
// MJM 20030116 - Don't want to do this. Multiple platforms are allowed to
// have the same transport ID & type. Example: Historical versions.
			// Use transport media to see if this platform already exists in
			// the database.
//			int id = getIdByTransportMedia(p);
//			if (id != Constants.undefinedId)
//			{
//				p.setId(id);
//				update(p);
//			}
//			else
				insert(p);
		}

		//System.out.println("	num PlatformSensors is " +
		//	p.platformSensors.size());
	}


	/**
	* Writes the changed  data for a pre-existing Platform back out to the
	* database, using an UPDATE query.
	* @param p the Platform
	*/
	private void update(Platform p)
		throws DatabaseException, SQLException
	{
		String q =
			"UPDATE Platform SET " +
			  "Agency = " + sqlString(p.agency) + ", " +
			  "IsProduction = " + sqlString(p.isProduction) + ", " +
			  "SiteId = " + sqlOptHasId(p.site) + ", " +
			  "ConfigId = " + sqlOptHasId(p.getConfig()) + ", " +
			  "Description = " + sqlOptString(p.description) + ", " +
			  "LastModifyTime = " + sqlOptDate(p.lastModifyTime) + ", " +
			  "Expiration = " + sqlOptDate(p.expiration);
		if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7)
			q = q + ", platformDesignator = " + 
				sqlOptString(p.getPlatformDesignator());

		q = q + " WHERE ID = " + p.getId();

		executeUpdate(q);

		// Now update the PlatformSensor and PlatformSensorProperty
		// records.
		// We'll take the easy road, which is to first delete them all
		// and then re-insert them.

		deletePlatformSensors(p);
		insertPlatformSensors(p);

		// Update the TransportMedia.  We'll take the easy route and
		// first delete them all, and then re-insert them.

		deleteTransportMedia(p);
		insertTransportMedia(p);

		if (getDatabaseVersion() >= 6)
		{
			PropertiesDAI propsDao = _dbio.makePropertiesDAO();
			
			try { propsDao.writeProperties("PlatformProperty", "platformId", p.getId(), 
				p.getProperties()); }
			catch (DbIoException e)
			{
				throw new DatabaseException(e.getMessage());
			}
			finally
			{
				propsDao.close();
			}
		}
	}

	/**
	* Writes a brand-new Platform into the SQL database, using an
	* INSERT query.
	* @param p the Platform
	*/
	private void insert(Platform p)
		throws SQLException, DatabaseException
	{
		DbKey id = getKey("Platform");
		p.setId(id);
		p.getDatabase().platformList.add(p);  // adds (or re-adds) with new ID.

		String q =
			"INSERT INTO Platform (" + getColTpl() + ")"
			+ " VALUES (" +
			  id + ", " +
			  sqlString(p.agency) + ", " +
			  sqlString(p.isProduction) + ", " +
			  sqlOptHasId(p.site) + ", " +
			  sqlOptHasId(p.getConfig()) + ", " +
			  sqlOptString(p.description) + ", " +
			  sqlOptDate(p.lastModifyTime) + ", " +
			  sqlOptDate(p.expiration);

		if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7)
			q = q + ", " + sqlOptString(p.getPlatformDesignator(), 24);

		q += ")";

		executeUpdate(q);

		// Write the PlatformSensor and PlatformSensorProperty entities
		insertPlatformSensors(p);

		// Now insert all the TransportMediums that are owned by this
		// Platform.  Since the Platform is new, we are guaranteed that
		// each TransportMedium is also new.
		insertTransportMedia(p);

		if (getDatabaseVersion() >= 6)
		{
			PropertiesDAI propsDao = _dbio.makePropertiesDAO();
			
			try { propsDao.writeProperties("PlatformProperty", "platformId", p.getId(), 
				p.getProperties()); }
			catch (DbIoException e)
			{
				throw new DatabaseException(e.getMessage());
			}
			finally
			{
				propsDao.close();
			}
		}
	}

	/**
	* This inserts all the TransportMedia associated with this Platform.
	* The Platform must have already had its SQL database ID set.
	* @param p the Platform
	*/
	public void insertTransportMedia(Platform p)
		throws SQLException, DatabaseException
	{
		DbKey id = p.getId();
		Vector<TransportMedium> v = p.transportMedia;
		for (int i = 0; i < v.size(); ++i)
		{
			TransportMedium tm = v.get(i);
			insertTransportMedium(id, tm);
		}
	}

	/**
	* This writes the PlatformSensor and PlatformSensorProperty records for
	* a particular Platform.  Note that records are only written if the
	* PlatformSensor object is not empty.
	* @param p the Platform
	*/
	public void insertPlatformSensors(Platform p)
		throws SQLException, DatabaseException
	{
		Iterator<PlatformSensor> i = p.getPlatformSensors();
		while (i.hasNext())
		{
			PlatformSensor ps = i.next();
			insert(ps);
		}
	}

	/**
	* This inserts a PlatformSensor and its associated
	* PlatformSensorProperty's into the database, if the PlatformSensor
	* is not empty.
	* @param ps the PlatformSensor
	*/
	public void insert(PlatformSensor ps)
		throws SQLException, DatabaseException
	{
		if (ps.isEmpty()) return;

		DbKey pid = ps.platform.getId();
		int sn = ps.sensorNumber;

		int usgsDdno = ps.getUsgsDdno();
		if (usgsDdno <= 0)
		{
			String s = PropertiesUtil.rmIgnoreCase(ps.getProperties(), "DDNO");
			if (s != null)
			{
				try { usgsDdno = Integer.parseInt(s.trim()); }
				catch(NumberFormatException ex)
				{
					usgsDdno = 0;
					ps.getProperties().setProperty("DDNO", s);
				}
			}
		}

		String q = "INSERT INTO PlatformSensor VALUES (" +
					 pid + ", " +
					 sn + ", " +
					 sqlOptHasId(ps.site);
		if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_7)
		{
			if (usgsDdno <= 0)
				q = q + ", NULL";
			else
				q = q + ", " + usgsDdno;
		}
		else if (usgsDdno > 0)
			ps.getProperties().setProperty("DDNO", "" + usgsDdno);
		q += ")";

		executeUpdate(q);

		PropertiesDAI propsDao = _dbio.makePropertiesDAO();
		try { propsDao.writeProperties("PlatformSensorProperty", "platformID", "SensorNumber",
			ps.platform.getId(), ps.sensorNumber, ps.getProperties()); }
		catch (DbIoException e)
		{
			throw new DatabaseException(e.getMessage());
		}
		finally
		{
			propsDao.close();
		}
	}

	/**
	* Insert a new TransportMedium into the database.
	* @param platformId the database ID of the platform
	* @param tm the TransportMedium
	*/
	public void insertTransportMedium(DbKey platformId, TransportMedium tm)
		throws SQLException, DatabaseException
	{
		String q;
		if (getDatabaseVersion() < DecodesDatabaseVersion.DECODES_DB_6)
		{
			if (tm.getTimeAdjustment() != 0)
				warning(
					"Cannot save time adjustment in version 5 SQL database.");
			if (tm.getPreamble() != Constants.preambleUndefined)
				warning(
					"Cannot save Transport Medium Preamble in version 5 SQL "
					+ "database.");

			String medType = tm.getMediumType();
			String medId = tm.getMediumId();
			if (medType == null || medType.trim().length() == 0
			 || medId == null || medId.trim().length() == 0)
			{
				warning("Skipping null transport medium");
				return;
			}
			q = "INSERT INTO TransportMedium VALUES (" +
			  	platformId + ", " +
			  	sqlReqString(tm.getMediumType()) + ", " +
			  	sqlReqString(tm.getMediumId()) + ", " +
			  	sqlOptString(tm.scriptName) + ", " +
			  	"'" + Constants.dataOrderUndefined + "', " +
			  	sqlOptInt(tm.channelNum) + ", " +
			  	sqlOptInt(tm.assignedTime) + ", " +
			  	sqlOptInt(tm.transmitWindow) + ", " +
			  	sqlOptInt(tm.transmitInterval) + ", " +
			  	sqlOptHasId(tm.equipmentModel) +
				")";
		}
		else if (getDatabaseVersion() < 7) 
			// Version 6 removed dataOrder and added time adjustment.
		{
			q = "INSERT INTO TransportMedium VALUES (" +
			  	platformId + ", " +
			  	sqlReqString(tm.getMediumType()) + ", " +
			  	sqlReqString(tm.getMediumId()) + ", " +
			  	sqlOptString(tm.scriptName) + ", " +
			  	sqlOptInt(tm.channelNum) + ", " +
			  	sqlOptInt(tm.assignedTime) + ", " +
			  	sqlOptInt(tm.transmitWindow) + ", " +
			  	sqlOptInt(tm.transmitInterval) + ", " +
			  	sqlOptHasId(tm.equipmentModel) + ", " +
			  	Integer.toString(tm.getTimeAdjustment()) + ", " +
				"'" + tm.getPreamble() + "'" +
			")";
		}
		else // Version 6 added time zone.
		{
			q = "INSERT INTO TransportMedium(PlatformId,MediumType,MediumId,ScriptName,ChannelNum," +
				"AssignedTime,TransmitWindow,TransmitInterval,EquipmentId,TimeAdjustment," +
				"Preamble,TimeZone) " +
			"VALUES (" +
			  	platformId + ", " +
			  	sqlReqString(tm.getMediumType()) + ", " +
			  	sqlReqString(tm.getMediumId()) + ", " +
			  	sqlOptString(tm.scriptName) + ", " +
			  	sqlOptInt(tm.channelNum) + ", " +
			  	sqlOptInt(tm.assignedTime) + ", " +
			  	sqlOptInt(tm.transmitWindow) + ", " +
			  	sqlOptInt(tm.transmitInterval) + ", " +
			  	sqlOptHasId(tm.equipmentModel) + ", " +
			  	Integer.toString(tm.getTimeAdjustment()) + ", " +
				"'" + tm.getPreamble() + "', " +
			  	sqlOptString(tm.getTimeZone()) +
			")";
		}

		try { executeUpdate(q); }
		catch(SQLException ex)
		{
			Logger.instance().warning("Error on query '" + q + "': " + ex);
		}
	}

	/**
	* Deletes a platform from the database, including its transport
	* media, it's PlatformSensors and PlatformSensorProperties.
	* It's configuration is NOT deleted.
	* The Platform object has it's ID unset, and it is removed from
	* its RecordList.
	* @param p the Platform
	*/
	public void delete(Platform p)
		throws SQLException, DatabaseException
	{
		PlatformStatusDAI platformStatusDAO = _dbio.makePlatformStatusDAO();
		try { platformStatusDAO.deletePlatformStatus(p.getId()); }
		catch(DbIoException ex)
		{
			throw new DatabaseException("Cannot delete platform status for platform with id="
				+ p.getId() + ": " + ex);
		}
		finally { platformStatusDAO.close(); }
		deletePlatformSensors(p);
		deleteTransportMedia(p);
		if (getDatabaseVersion() >= DecodesDatabaseVersion.DECODES_DB_6)
		{
			PropertiesDAI propsDao = _dbio.makePropertiesDAO();
			
			try { propsDao.deleteProperties("PlatformProperty", "platformId", p.getId()); }
			catch (DbIoException e)
			{
				throw new DatabaseException(e.getMessage());
			}
			finally
			{
				propsDao.close();
			}
		}

		String q = "DELETE FROM Platform WHERE ID = " + p.getId();
		executeUpdate(q);

		// NOTE: it's up to the caller to remove from Database collection(s).
	}

	/**
	* Delete all the PlatformSensor and PlatformSensorProperty records
	* associated with a Platform.  The Platform must have its SQL
	* database ID set.
	* @param p the Platform
	*/
	public void deletePlatformSensors(Platform p)
		throws DatabaseException, SQLException
	{
		PropertiesDAI propsDao = _dbio.makePropertiesDAO();
		try { propsDao.deleteProperties("PlatformSensorProperty", "platformID", p.getId()); }
		catch (DbIoException e)
		{
			throw new DatabaseException(e.getMessage());
		}
		finally
		{
			propsDao.close();
		}
		String q = "DELETE FROM PlatformSensor WHERE PlatformId = " + p.getId();
		tryUpdate(q);
	}

	/**
	* This deletes all the TransportMedium's belonging to this Platform.
	* The Platform must have already had its SQL database ID set.
	* @param p the Platform
	*/
	public void deleteTransportMedia(Platform p)
		throws DatabaseException, SQLException
	{
		String q = "DELETE FROM TransportMedium WHERE PlatformId = " + p.getId();
		tryUpdate(q);
	}

	/**
	 * Find the platform ID with the matching medium type/id. 
	 * If timestamp is provided, find the platform with the earliest expiration
	 * date after the specified timestamp.
	 * expiration date 
	 * @param mediumType
	 * @param mediumId
	 * @param stamp
	 * @return
	 */
	public DbKey lookupPlatformId(String mediumType, String mediumId,
		Date timeStamp)
	{
//System.out.println("PlatformListIO.lookupPlatformId(" + mediumType
//+ ", " + mediumId + ", " + timeStamp + ")");

		String q = "SELECT PlatformId, expiration "
			+ "FROM TransportMedium, Platform WHERE ";
		mediumType = mediumType.toLowerCase();
		if (mediumType.startsWith("goes"))
		{
			q = q + "(lower(mediumType) = " 
				+ sqlReqString(Constants.medium_Goes.toLowerCase())
				+ " OR lower(mediumType) = "
				+ sqlReqString(Constants.medium_GoesST.toLowerCase())
				+ " OR lower(mediumType) = "
				+ sqlReqString(Constants.medium_GoesRD.toLowerCase())
				+ ")";
		}
		else
			q = q + "lower(mediumType) = " + sqlReqString(mediumType);
		q = q + " AND mediumId = " + sqlReqString(mediumId);
		q = q + " AND TransportMedium.platformId = Platform.id";
		if (timeStamp == null)
			timeStamp = new Date();
		try
		{
			Statement stmt = createStatement();
			ResultSet rs = stmt.executeQuery(q);
			DbKey pid = Constants.undefinedId;
			DbKey bestId = Constants.undefinedId;
			Date bestExp = null;
			while (rs != null && rs.next())
			{
				pid = DbKey.createDbKey(rs, 1);
				if (rs.wasNull())
					continue;
				Date expiration = getTimeStamp(rs, 2, null);
//System.out.println("Checking pid=" + pid + ", exp=" + expiration);	
				if (expiration != null
				 && timeStamp.compareTo(expiration) > 0)
					// this timestamp is after expiration, skip it.
					continue;
				
				if (expiration == null) // 'current' version
				{
					if (bestId == Constants.undefinedId)
						bestId = pid;
				}
				else if (bestExp == null || bestExp.compareTo(expiration) > 0)
				{
					bestId = pid;
					bestExp = expiration;
				}
			}
			stmt.close();
			return bestId;
		}
		catch(SQLException ex)
		{
			warning("SQL Execution on '" + q + "': " + ex);
			return Constants.undefinedId;
		}
	}
	
	public DbKey lookupCurrentPlatformId(SiteName sn, 
		String designator, boolean useDesignator)
		throws DatabaseException
	{
		String q = "SELECT platform.id FROM "
			+ "platform, sitename "
			+ "WHERE platform.siteid = sitename.siteid "
			+ "AND lower(sitename.nametype) = " 
				+ sqlReqString(sn.getNameType().toLowerCase())
			+ " AND lower(sitename.sitename) = "
				+ sqlReqString(sn.getNameValue().toLowerCase())
			+ " AND platform.expiration is null";
		if (useDesignator && designator != null)
			q = q + " AND lower(platform.platformdesignator) = "
				+ sqlReqString(designator.toLowerCase());
		try
		{
			Statement stmt = createStatement();
			ResultSet rs = stmt.executeQuery(q);
			DbKey pid = Constants.undefinedId;
			if (rs != null && rs.next())
				pid = DbKey.createDbKey(rs, 1);
			stmt.close();
			return pid;
		}
		catch(SQLException ex)
		{
			warning("SQL Execution on '" + q + "': " + ex);
			return Constants.undefinedId;
		}
	}


	/**
	* Returns the last-modify-time for this platform in the database.
	* When the editor modifies a config, equipment-model, or site, it also
	* updatess the platform record.
	* @param p the Platform
	*/
	public Date getLMT(Platform p)
		throws DatabaseException
	{
		try
		{
			Statement stmt = createStatement();
			String q = 
				"SELECT lastModifyTime FROM Platform WHERE id = " + p.getId();
			ResultSet rs = stmt.executeQuery(q);

			// Should be only 1 record returned.
			if (rs == null || !rs.next())
			{
				warning("Cannot get SQL LMT for platform ID " + p.getId());
				return null;
			}

			Date ret = getTimeStamp(rs, 1, (Date)null);
			stmt.close();
			return ret;
		}
		catch(SQLException ex)
		{
			warning("SQL Error reading LMT for platform ID " + p.getId()
				+ ": " + ex);
			return null;
		}
	}
	
	public Date getListLMT()
	{
		String q = "SELECT MAX(lastModifyTime) from Platform";
		try
		{
			Statement stmt = createStatement();
			ResultSet rs = stmt.executeQuery(q);

			// Should be only 1 record returned.
			if (rs == null || !rs.next())
			{
				warning("Cannot get SQL LMT for platform list.");
				return new Date(0L);
			}

			Date ret = getTimeStamp(rs, 1, new Date(0L));
			stmt.close();
			return ret;
		}
		catch(SQLException ex)
		{
			warning("SQL Error reading LMT for platform list: " + ex);
			return new Date(0L);
		}
	}

	
	public ArrayList<String> readNetworKListName(String transportId)
	{
		ArrayList<String> networkListArray =new ArrayList<String>();
		String q = "SELECT networklist.name FROM networklist, networklistentry WHERE networklistentry.transportid= '"+transportId+"' AND networklistentry.networklistid=networklist.id";
		try
		{
			Statement stmt = createStatement();
			ResultSet rs = stmt.executeQuery(q);

			// Should be only 1 record returned.
			while(rs.next())
			{
				networkListArray.add(rs.getString(1));
			}

			
			stmt.close();
			return networkListArray;
		}
		catch(SQLException ex)
		{
			warning("SQL Error reading networkList: " + ex);
			return null;
		}	
	}
	public void updateTransportId(String oldtransportId, String newTransportId)throws DatabaseException, SQLException
	{
		
		String q="UPDATE networklistentry set transportid='"+newTransportId+"' where transportid='"+oldtransportId+"'";
		
			executeUpdate(q);
	}
}