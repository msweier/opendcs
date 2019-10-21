#!/bin/bash

#===========================================================================
# Run this script to install the OPENDCS Time Series Database Schema on
# an Oracle DBMS.
#===========================================================================

DH=%INSTALL_PATH

echo "This script will install the OPENDCS Time Series Database Schema on an Oracle DBMS."
echo -n "Have you edited defines.sh with your specific settings (y/n) ?"
read answer
if [ "$answer" != "y" ] && [ "$answer" != "Y" ]
then
  echo "Before running this script, edit defines.sh."
  exit
fi

#
# Source the defines file
#
if [ ! -f defines.sh ]
then
  echo "There is no 'defines.sh' in the current directory."
  echo "CD to the opendcs-oracle schema directory before running this script."
  exit
fi
. defines.sh

echo -n "Running createDb.sh at " >$LOG
date >> $LOG
echo >> $LOG

echo "====================" >>$LOG
echo "Creating defines.sql" >>$LOG
echo "Creating defines.sql"
echo "-- defines.sql" >defines.sql
echo "-- Created automatically from defines.sh. " >>defines.sql
echo "-- DO NOT EDIT THIS FILE" >>defines.sql
echo >>defines.sql
echo "undefine LOG;" >>defines.sql
echo "define LOG = $LOG;" >>defines.sql
echo "undefine TBL_SPACE_DIR;" >>defines.sql
echo "define TBL_SPACE_DIR = $TBL_SPACE_DIR;" >>defines.sql
echo "undefine TBL_SPACE_DATA;" >>defines.sql
echo "define TBL_SPACE_DATA = $TBL_SPACE_DATA;" >>defines.sql
echo "undefine TBL_SPACE_TEMP;" >>defines.sql
echo "define TBL_SPACE_TEMP = $TBL_SPACE_TEMP;" >>defines.sql
echo "undefine TSDB_ADM_SCHEMA;" >>defines.sql
echo "define TSDB_ADM_SCHEMA = $TSDB_ADM_SCHEMA;" >>defines.sql
echo "undefine TSDB_ADM_PASSWD;" >>defines.sql
echo "define TSDB_ADM_PASSWD = $TSDB_ADM_PASSWD;" >>defines.sql
echo "undefine TBL_SPACE_SPEC;" >>defines.sql
echo "define TBL_SPACE_SPEC = 'tablespace $TBL_SPACE_DATA'" >>defines.sql

echo "====================" >>$LOG
echo "Creating tablespaces" >>$LOG
echo "Creating tablespaces"
rm -f tablespace.log
echo sqlplus $DBSUPER/$DBSUPER_PASSWD@//$DBHOST:$DBPORT/$DB_TNSNAME as sysdba @tablespace.sql >>$LOG
sqlplus $DBSUPER/$DBSUPER_PASSWD@//$DBHOST:$DBPORT/$DB_TNSNAME as sysdba @tablespace.sql
cat tablespace.log >>$LOG
rm tablespace.log

echo "======================" >>$LOG
echo "Creating roles & users" >>$LOG
echo "Creating roles & users"
rm -f roles.log
echo sqlplus $DBSUPER/$DBSUPER_PASSWD@//$DBHOST:$DBPORT/$DB_TNSNAME as sysdba @roles.sql >>$LOG
sqlplus $DBSUPER/$DBSUPER_PASSWD@//$DBHOST:$DBPORT/$DB_TNSNAME as sysdba @roles.sql
cat roles.log >>$LOG
rm roles.log


echo "=================================" >>$LOG
echo "Creating combined schema file ..." >>$LOG
echo "Creating combined schema file ..."
cp combined_hdr.sql combined.sql

cat opendcs.sql >> combined.sql
cat dcp_trans_expanded.sql >>combined.sql
./expandTs.sh $NUM_TABLES $STRING_TABLES
cat ts_tables_expanded.sql >>combined.sql
cat alarm.sql >>combined.sql
rm ts_tables_expanded.sql
./makePerms.sh combined.sql
cat setPerms.sql >>combined.sql
rm setPerms.sql
cat sequences.sql >>combined.sql

echo >> combined.sql
echo "-- Set Version Numbers" >> combined.sql
now=`date`
echo 'delete from DecodesDatabaseVersion; ' >> combined.sql
echo "insert into DecodesDatabaseVersion values(17, 'Installed $now');" >> combined.sql
echo 'delete from tsdb_database_version; ' >> combined.sql
echo "insert into tsdb_database_version values(17, 'Installed $now');" >> combined.sql

for n in `seq 1 $NUM_TABLES`
do
	echo "insert into storage_table_list values($n, 'N', 0, 0);" >> combined.sql
done

for n in `seq 1 $STRING_TABLES`
do
	echo "insert into storage_table_list values($n, 'S', 0, 0);" >> combined.sql
done

echo "spool off" >>combined.sql
echo "exit;" >> combined.sql


echo "======================" >>$LOG
echo "creating tables, indexes, and sequences" >>$LOG
echo "creating tables, indexes, and sequences"
rm combined.log
echo sqlplus $TSDB_ADM_SCHEMA/$TSDB_ADM_PASSWD@//$DBHOST:$DBPORT/$DB_TNSNAME @combined.sql >>$LOG
sqlplus $TSDB_ADM_SCHEMA/$TSDB_ADM_PASSWD@//$DBHOST:$DBPORT/$DB_TNSNAME @combined.sql
cat combined.log >>$LOG
rm combined.log

echo >>$LOG
echo "Importing Enumerations from edit-db ..." >>$LOG
echo "Importing Enumerations from edit-db ..."
$DH/bin/dbimport -l $LOG -r $DH/edit-db/enum/*.xml

echo >>$LOG
echo "Importing Standard Engineering Units and Conversions from edit-db ..." >>$LOG
echo "Importing Standard Engineering Units and Conversions from edit-db ..."
$DH/bin/dbimport -l $LOG -r $DH/edit-db/eu/EngineeringUnitList.xml

echo >>$LOG
echo "Importing Standard Data Types from edit-db ..." >>$LOG
echo "Importing Standard Data Types from edit-db ..."
$DH/bin/dbimport -l $LOG -r $DH/edit-db/datatype/DataTypeEquivalenceList.xml

echo >>$LOG
echo "Importing Presentation Groups ..." >>$LOG
echo "Importing Presentation Groups ..."
$DH/bin/dbimport -l $LOG -r $DH/edit-db/presentation/*.xml

echo >>$LOG
echo "Importing standard computation apps and algorithms ..." >>$LOG
echo "Importing standard computation apps and algorithms ..."
$DH/bin/compimport -l $LOG $DH/imports/comp-standard/*.xml

echo >>$LOG
echo "Importing DECODES loading apps ..." >>$LOG
echo "Importing DECODES loading apps ..."
$DH/bin/dbimport -l $LOG -r $DH/edit-db/loading-app/*.xml

rm defines.sql
