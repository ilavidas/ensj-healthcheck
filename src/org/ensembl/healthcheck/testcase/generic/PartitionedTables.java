/*
 * Copyright (C) 2004 EBI, GRL
 * 
 * This library is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA
 * 02111-1307 USA
 */

package org.ensembl.healthcheck.testcase.generic;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.ensembl.healthcheck.DatabaseRegistryEntry;
import org.ensembl.healthcheck.ReportManager;
import org.ensembl.healthcheck.testcase.Priority;
import org.ensembl.healthcheck.testcase.SingleDatabaseTestCase;

/**
 * Check if any tables are partitioned. None should be yet, until their definitions are updated in table.sql.
 */
public class PartitionedTables extends SingleDatabaseTestCase {

	/**
	 * Creates a new instance of PartitionedTables
	 */
	public PartitionedTables() {

		addToGroup("release");
		setDescription("Check whether tables have been partitioned.");
		setPriority(Priority.AMBER);
		setEffect("Tables should only be partitioned if the partitions are defined in table.sql.");
		setTeamResponsible("ReleaseCoordinator");

	}

	/**
	 * Run the test.
	 * 
	 */
	public boolean run(DatabaseRegistryEntry dbre) {

		boolean result = true;

		Connection con = dbre.getConnection();

		try {

			Statement stmt = con.createStatement();
			ResultSet rs = stmt.executeQuery("SHOW TABLE STATUS WHERE create_options LIKE '%partitioned%'");

			while (rs.next()) {
				
				String table = rs.getString(1);
				ReportManager.problem(this, con, table + " is partitioned but shouldn't be.");
				result = false;
				
			}

			rs.close();

			stmt.close();

		} catch (Exception e) {

			e.printStackTrace();
			System.exit(1);

		}

		if (result == true) {
		
			ReportManager.correct(this, con, "No tables are partitioned");
		}
		
		return result;

	} // run

	// -----------------------------------------------------------------------

} // AnalyseTables
