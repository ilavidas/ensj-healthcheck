package org.ensembl.healthcheck.testcase.funcgen;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.ArrayList;
import java.sql.Array;

import org.ensembl.healthcheck.DatabaseRegistryEntry;
import org.ensembl.healthcheck.DatabaseType;
import org.ensembl.healthcheck.ReportManager;
import org.ensembl.healthcheck.Team;
import org.ensembl.healthcheck.testcase.Priority;
import org.ensembl.healthcheck.testcase.SingleDatabaseTestCase;

public class RegulatorySets extends SingleDatabaseTestCase {

	
	/**
	 * Create a new instance
	 */
	public RegulatorySets() {
		addToGroup("post_regulatorybuild");
		addToGroup("funcgen");//do we need this group and the funcgen-release group?
		addToGroup("funcgen-release");

		setTeamResponsible(Team.FUNCGEN);

		setDescription("Checks if sets have appropriate associations and statues entries");
		setPriority(Priority.AMBER);
		setEffect("Displays may fail or omit some data");
		setFix("Run update_DB_for_release (in fix mode) or run suggested USEFUL SQL");
	}
	
	
	/**
	 * This only applies to funcgen databases.
	 */
	public void types() {
		addAppliesToType(DatabaseType.FUNCGEN);
		
		//Do we really need these removes?
		removeAppliesToType(DatabaseType.OTHERFEATURES);
		removeAppliesToType(DatabaseType.CDNA);
		removeAppliesToType(DatabaseType.CORE);
		removeAppliesToType(DatabaseType.VARIATION);
		removeAppliesToType(DatabaseType.COMPARA);

	}
	
		
	/**
	 * Run the test.
	 * We will check for all sets associated with regulatory build have appropriate meta_keys, supporting_sets 
	 * and status entries
	 *
	 * @param dbre
	 *          The database to use.
	 * @return true if the test passed.
	 * 
	 */
	public boolean run(DatabaseRegistryEntry dbre) {

		boolean result = true;
		Connection efgCon = dbre.getConnection();
		//Test for other meta keys here: reg build version?

		HashMap fsetInfo    = new HashMap<String, HashMap>(); 
		String[] metaKeys  = {"feature_set_ids", "focus_feature_set_ids", "feature_type_ids"};	
				
		try {
			int regSetCount = getRowCount(efgCon, "SELECT count(*) from feature_set where type='regulatory'");
			String[] cellTypes = new String[regSetCount];
			int count = 0;
			
			Statement stmt = efgCon.createStatement();
			ResultSet rs = stmt.executeQuery("SELECT feature_set_id, name from feature_set where type='regulatory'");
			
			while (rs.next()) {			
				HashMap fsInfo    = new HashMap<String, HashMap>(); 
				String fsetName = rs.getString("name");
				
				// TEST FOR ARCHIVED SETS
				//Need to do this for data_set table too?
				if(fsetName.matches(".*_v[0-9]+$")) {
					ReportManager.problem(this, efgCon, "Found archived regulatory FeatureSet:\n" + 
							  fsetName + "\nUse rollback_experiment.pl to remove these");
					result = false;	
					continue;	
				}			
				
				fsInfo.put("name", fsetName);						
				String cellType = fsetName.replaceAll("RegulatoryFeatures:", "");	
				fsInfo.put("cell_type", fsetName);	
				cellTypes[count] = cellType;
			
				// GET META_KEYS
				for (int i=0; i < metaKeys.length; i++) {
				    String fullKey = "regbuild." + cellType + "." + metaKeys[i];		    
			    	Statement stmtMeta = efgCon.createStatement();
					ResultSet rsMeta = stmtMeta.executeQuery("SELECT meta_value from meta where meta_key='" +
							fullKey + "'");
								
					if(! rsMeta.next()){
						ReportManager.problem(this, efgCon, "Found absent meta_key:\t" + 
								fullKey);
						result = false;
					}else{
						fsInfo.put(metaKeys[i], rsMeta.getString("meta_value"));
					}								
				}
				    									
				fsetInfo.put(rs.getString("feature_set_id"), fsInfo);
				++count;	
			}
			

			// TEST FOR OLD/ANOMALOUS META_KEYS		
			stmt = efgCon.createStatement();
			rs = stmt.executeQuery("SELECT meta_key from meta where meta_key like 'regbuild%ids%'");
			
			while (rs.next()) {
				String metaKey = rs.getString("meta_key");
				
				if(metaKey.matches(".*_v[0-9]+$") ){
					ReportManager.problem(this, efgCon, "Found archived meta_key:\t" + 
							metaKey);
					result = false;
					continue;
				}
			
				String metaTmp   = metaKey.replaceAll("regbuild.", "");		
				String cellType  = metaTmp.replaceAll("\\..*_ids", ""); //will this work without compiling a pattern
				String keyType   = metaTmp.replaceAll(cellType + ".*\\.", ""); //Will this escape properly?
		
				if(! Arrays.asList(cellTypes).contains(cellType)){
					ReportManager.problem(this, efgCon, "Found cell type meta_key which is not represented as a " + 
							"FeatureSet:\t" + metaKey);
					result = false;		
				}	
			}
	
		
			//DEAL WITH EACH BUILD SEPARATELY
			//We already do most of this in HealthChecker.pm!
			Iterator rfsetIt = fsetInfo.keySet().iterator();	
		
			while(rfsetIt.hasNext()){ //Regulatory FeatureSets
				String fsetID = (String) rfsetIt.next();			
				HashMap fsInfo = (HashMap) fsetInfo.get(fsetID);
					
				// Check RegFeat data_set
				stmt = efgCon.createStatement();
				rs = stmt.executeQuery("SELECT name, data_set_id from data_set where feature_set_id=" + fsetID);
			
				if(! rs.next()){
					ReportManager.problem(this, efgCon, "Found absent data_set:\t" + fsInfo.get("name"));
					result = false;
					continue; //while(fsetIt.hasNext())
				}
					
				// Set names matches?
				if(! fsInfo.get("name").equals(rs.getString("name"))){
					ReportManager.problem(this, efgCon, "Found name mismatch between FeatureSet " +
							fsInfo.get("name") + " and linked DataSet " + rs.getString("name"));
					result = false;		
				}
				
				String dsetID = rs.getString("data_set_id");
				
						
				//GET ALL SUPPORTING SET INFO
				stmt         = efgCon.createStatement();
				ResultSet rsDsetSupport = stmt.executeQuery("SELECT ss.supporting_set_id as 'ss_feature_set_id', " +
						"fs.feature_set_id as 'fs_feature_set_id', ds.data_set_id as 'ds_data_set_id', ss1.supporting_set_id as 'ss_result_set_id'" + 
						"from supporting_set ss left join (feature_set fs left join " +
						"(data_set ds left join supporting_set ss1 on ds.data_set_id=ss1.data_set_id and ss1.type='result') " +
						"on fs.feature_set_id=ds.feature_set_id) on fs.feature_set_id=ss.supporting_set_id " + 
						"where ss.type='feature' and ss.data_set_id=" + dsetID);	
			                                                                                                	
				count = 0;
				String[] metaFsetIDs  = ((String) fsInfo.get("feature_set_ids")).split(",");
				String[] metaFtypeIDs = ((String) fsInfo.get("feature_type_ids")).split(",");
				
				//String[] ssFsetIDs    = (String[])((Array) rsDsetSupport.getArray("ss_feature_set_id")).getArray();
				//String[] fsFsetIDs    = (String[])((Array) rsDsetSupport.getArray("fs_feature_set_id")).getArray();
				//String[] dsDsetIDs    = (String[])((Array) rsDsetSupport.getArray("ds_data_set_id")).getArray();
				//String[] ssRsetIDs    = (String[])((Array) rsDsetSupport.getArray("ss_result_set_id")).getArray();
			
		
				ArrayList<String> ssFsetIDs = new ArrayList<String>();				
				ArrayList<String> fsFsetIDs = new ArrayList<String>();
				ArrayList<String> dsDsetIDs = new ArrayList<String>();
				ArrayList<String> ssRsetIDs = new ArrayList<String>();
	
				while(rsDsetSupport.next()){
					ssFsetIDs.add(rsDsetSupport.getString("ss_feature_set_id"));
					fsFsetIDs.add(rsDsetSupport.getString("fs_feature_set_id"));
					dsDsetIDs.add(rsDsetSupport.getString("ds_data_set_id"));
					ssRsetIDs.add(rsDsetSupport.getString("ss_result_set_id"));
				}
		
				
				//CHECK META KEY feature_set_ids
				boolean sqlSafe = true;
				
				for(int i=0; i < metaFsetIDs.length; i++){
				
					if(! ssFsetIDs.contains(metaFsetIDs[i])){
						ReportManager.problem(this, efgCon, "Found feature_set_id in meta_key:\t" +
								"regbuild." + fsInfo.get("cell_type") + ".feature_set_ids which is not " +
								"present as a supporting_set_id for DataSet " + fsInfo.get("name"));
						result  = false;	
						sqlSafe = false;
						continue;
					}
					
					++count;
					
					//Check feature_type is correct					
					stmt              = efgCon.createStatement();
					ResultSet ssFtype = stmt.executeQuery("SELECT feature_type_id from feature_set " +
							"where feature_set_id=" + metaFsetIDs[i]);
					
					
					if(! ssFtype.next()){
						//Need to test this as we have only data from meta and supporting_set so far!
						ReportManager.problem(this, efgCon, "Found absent supporting FeatureSet from regbuild." + 
								fsInfo.get("cell_type") + ".feature_set_ids:\t" + metaFsetIDs[i]);
						//This will also be reported in the CHECK SETS/STATES loop below
						continue;
					}
					
					if(! ssFtype.getString("feature_type_id").equals(metaFtypeIDs[i])){
						ReportManager.problem(this, efgCon, "Found mismatch between meta feature_set_id(" + 
								ssFtype.getString("feature_type_id") + ") and meta feature_type_id(" + 
								metaFtypeIDs[i] + ") for " + fsInfo.get("cell_type"));
						result = false;	
						sqlSafe = false;
					}												
				}
		
				//CHECK META SIZE
				if(ssFsetIDs.size() != count){
					ReportManager.problem(this, efgCon, "");
					result = false;	
					sqlSafe = false;
				}
				
				//CHECK META KEY focus_feature_set_ids		
				String[] metaFFsetIDs  = ((String) fsInfo.get("focus_feature_set_ids")).split(",");
				
				
				for(int i=0; i < metaFFsetIDs.length; i++){
					
					if(! Arrays.asList(metaFsetIDs).contains(metaFFsetIDs[i])){
						ReportManager.problem(this, efgCon, "Found feature_set_id in meta_key:\t" +
								"regbuild." + fsInfo.get("cell_type") + ".focus_feature_set_ids which is not " +
								"present in regbuild." + fsInfo.get("cell_type") + ".feature_set_ids");
						result = false;	
						//sqlSafe = false;// Is it just the focus key that is wrong?
						//Will have already set this otherwise
						continue;
					}
					
					//Could check feature_type is_focus here too?					
				}
				
				//Could check length matches for MultiCell set?
				//Could actually remove this meta_key for MultiCell as it should be the same feature_set_ids?
				
				//CHECK SUPPORTING FEATURE/DATA/RESULT SETS, STATES AND DBFILE_REGISTRY	
				String usefulSQL = "";
				String[] dsetStates =  {"DISPLAYABLE"};
				//my @rset_states = (@dset_states, 'DAS_DISPLAYABLE', $imp_cs_status);
				//No method on basic [] array to add other arrays elements in assigment
				//The declaration code expects a String and will not interpolate an String[]
				//as separate Strings
				//String[] rsetStates = {Arrays.asList(dsetStates).,  };
				//Need to get current CS name here for IMPORTED_'CS_NAME' status
				//my @fset_states = (@rset_states, 'MART_DISPLAYABLE');				  
				String[] rsetStates  = {"DISPLAYABLE", "DAS_DISPLAYABLE"}; //Conditional test for RESULT_FEATURE_SET is below
				String[] fsetStates  = {"DISPLAYABLE", "DAS_DISPLAYABLE", "MART_DISPLAYABLE"};
				//String[] windowSizes = {};//leave file test this to Collection test?
				ArrayList<String> absentStates = new ArrayList<String>(); 
			
				
				
				
				//Could these usefulSql status commands be using IDs which are not valid or null?
				//Yes these are unsafe until the the meta_keys/supporting_sets are corrected! 
				//Change INSERT IGNORE into just select to encourage the HC checker to look at the output first?
			
				//Set up ArrayLists so we can report once for each set after the main loop
				ArrayList<String> problemSupportingFsets = new ArrayList<String>();
				ArrayList<String> problemSupportingDsets = new ArrayList<String>();
				ArrayList<String> problemSupportingRsets = new ArrayList<String>();
				
				
				for(int i=0; i < fsFsetIDs.size(); i++){
								
					if(fsFsetIDs.get(i) == null){ //fset check
						ReportManager.problem(this, efgCon, "RegulatoryFeatures:" + fsInfo.get("cell_type") +
								" has absent supporting FeatureSet\t" + ssFsetIDs.get(i));
						result = false;	
						continue;						
					}
					else{ //fset status checks here						
						absentStates = getAbsentStates(efgCon, fsetStates, "feature_set", fsFsetIDs.get(i).toString());
						
						if(absentStates.size() != 0){
							problemSupportingFsets.add(fsFsetIDs.get(i));
						}
						
						
						if(dsDsetIDs.get(i) == null){ //dset check
							ReportManager.problem(this, efgCon, "RegulatoryFeatures:" + fsInfo.get("cell_type") +
									" has absent DataSet for supporting FeatureSet\t" + fsFsetIDs.get(i));
							result = false;	
							continue;									
						}
						else{ //dset status checks here
							absentStates = getAbsentStates(efgCon, dsetStates, "data_set", dsDsetIDs.get(i).toString());
							
							if(absentStates.size() != 0){
								problemSupportingDsets.add(dsDsetIDs.get(i));
							}
									
													
							
							if(ssRsetIDs.get(i) == null){
								ReportManager.problem(this, efgCon, "RegulatoryFeatures:" + fsInfo.get("cell_type") 
										+ " has absent supporting_set ResultSet for supporting DataSet\t" + dsDsetIDs.get(i));
								result = false;	
								continue;										
							}
							else{ //rset tests and status checks here
								
								stmt = efgCon.createStatement();
								rs   = stmt.executeQuery("SELECT rs.name as rs_name, sn.name as sn_name, dbr.path from result_set rs " +
										"LEFT JOIN (status s join status_name sn ON " +
										"s.status_name_id=sn.status_name_id AND sn.name='RESULT_FEATURE_SET') " +
										"ON rs.result_set_id=s.table_id AND s.table_name='result_set' " + 
										"LEFT JOIN dbfile_registry dbr ON rs.result_set_id=dbr.table_id AND dbr.table_name='result_set' " +
										"WHERE rs.result_set_id=" + ssRsetIDs.get(i));
								
								if(! rs.next()){
									ReportManager.problem(this, efgCon, "RegulatoryFeatures:" + fsInfo.get("cell_type") +
											" supporting DataSet has absent supporting ResultSet:\t" + ssRsetIDs.get(i));
									result = false;
								}
								else{	
																		
									if(rs.getString("sn_name") == null){ //Should be a Collection
										//Test dbfile_registry entries match rset name
										//Leave file tests to separate HC which only deals with the dbfile_registry table and files
										String dbfPath = rs.getString("path");
																	
										
										if(dbfPath == null){
											//test result_set_input type too!
											
											ReportManager.problem(this, efgCon, 
														"Could not find dbfile_registry entry for ResultSet which is " +
														"not a RESULT_FEATURE_SET:\t" + ssRsetIDs.get(i));
											result = false;
										}
										else if(! dbfPath.matches(".*" + rs.getString("rs_name") + "/*")){//rset_name matches path?
											ReportManager.problem(this, efgCon, 
													"Found mismatch between ResultSet name and dbfile_registry.path:\t" +
													rs.getString("rs_name") + " vs " + dbfPath);
											result = false;
										}
									}
									
								
									absentStates = getAbsentStates(efgCon, rsetStates, "result_set", ssRsetIDs.get(i).toString());
									
									if(absentStates.size() != 0){
										//do this for whole set of supporting rsets?
										problemSupportingRsets.add(ssRsetIDs.get(i));								
									}
								}
							}//end of if(ssRsetIDs.get(i) == null){ else
						}
					}// end of if(fsFsetIDs.get(i) == null){ else
				}//end of for loop
		
				
				if(problemSupportingFsets.size() > 0){
					
					if(sqlSafe){
						usefulSQL = "\nUSEFUL SQL:\tINSERT IGNORE INTO status SELECT fs.feature_set_id, 'feature_set', sn.name from feature_set fs, status_name sn " +
						"WHERE sn.name in (" + Arrays.toString(fsetStates).replaceAll("[\\[\\]]", "") + ") AND fs.feature_set_id IN (" + 
						problemSupportingFsets.toString().replaceAll("[\\[\\]]", "") + ")";
					}
					
					ReportManager.problem(this, efgCon, "Found absent states (from " + Arrays.toString(fsetStates) + 
							") for supporting FeatureSets:\t" + problemSupportingFsets.toString() + usefulSQL);
					result = false;							
				}
				
				
				if(problemSupportingDsets.size() > 0){
			
					if(sqlSafe){
						usefulSQL = "\nUSEFUL SQL:\tINSERT IGNORE INTO status SELECT , 'data_set', sn.name from data_set set, status_name sn " +
						"WHERE sn.name in (" + Arrays.toString(dsetStates).replaceAll("[\\[\\]]", "") + ") " +
						"AND ds.data_set_id IN (" +	problemSupportingDsets.toString().replaceAll("[\\[\\]]", "") + ")";
					}	
					
					ReportManager.problem(this, efgCon, "Found absent states (from " + Arrays.toString(dsetStates).replaceAll("[\\[\\]]", "") 
							+ ") for supporting DataSet:\t" + problemSupportingDsets.toString() + usefulSQL);
					result = false;					
				}		
				
				if(problemSupportingRsets.size() > 0){

					if(sqlSafe){					
						usefulSQL = "\nUSEFUL SQL:\tINSERT IGNORE INTO status SELECT, 'result_set', sn.name from result_set rs, status_name sn " +
						"WHERE sn.name in (" + Arrays.toString(rsetStates).replaceAll("[\\[\\]]", "") +
						") AND rs.data_set_id IN (" + problemSupportingRsets.toString().replaceAll("[\\[\\]]", "") + ")";		
					}		
				
					ReportManager.problem(this, efgCon, "RegulatoryFeatures:" + fsInfo.get("cell_type") +
							" supporting DataSet supporting ResultSet with absent states ( from" + absentStates.toString().replaceAll("[\\[\\]]", "") + 
							"):\t" + problemSupportingDsets.toString() + usefulSQL);
					result = false;
				}
			}			
		}catch (SQLException se) {
			//Does this exit and return false?
			se.printStackTrace();
			//This currently still returns PASSED and does print the 'problem'!
			ReportManager.problem(this, efgCon, "Caught SQLException");
			result = false;	
		}
		
		return result;
	}		
	
	//Move this to SingleFuncgenTestCase?
	public ArrayList getAbsentStates(Connection efgCon, String[] statusNames, String tableName, String tableID){
		ArrayList<String> absentStates = new ArrayList<String>();
		
		try{
			Statement stmt = efgCon.createStatement();
			String sqlCmd = "";
			
			for (int i = 0; i< statusNames.length; i++){
				
				ResultSet rs = stmt.executeQuery("SELECT s.table_id from status s join status_name sn " +
						"ON s.status_name_id=sn.status_name_id WHERE sn.name='" + statusNames[i] +
						"' AND s.table_name='" + tableName + "' AND s.table_id=" + tableID);	
				
				if(! rs.next()){
					absentStates.add(statusNames[i]);
				}
			}
		
			
			
		}catch (SQLException se) {
			//Does this exit and return false?
			se.printStackTrace();
//			ReportManager.problem(this, efgCon, "Caught SQLException");
			
		}
		
		return absentStates;
	}
	
}
