/*
 * Copyright 2013 by pactera.edg.am Corporation. Address:HePingLi East Street No.11
 * 5-5, GZ,
 * 
 * All rights reserved.
 * 
 * This software is the confidential and proprietary information of pactera.edg.am
 * Corporation ("Confidential Information"). You shall not disclose such
 * Confidential Information and shall use it only in accordance with the terms
 * of the license agreement you entered into with pactera.edg.am.
 */

package com.pactera.edg.am.metamanager.extractor.adapter.extract.db.impl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import com.pactera.edg.am.metamanager.extractor.bo.cwm.core.ModelElement;
import com.pactera.edg.am.metamanager.extractor.bo.cwm.db.Column;
import com.pactera.edg.am.metamanager.extractor.bo.cwm.db.NamedColumnSet;
import com.pactera.edg.am.metamanager.extractor.bo.cwm.db.NamedColumnSetType;
import com.pactera.edg.am.metamanager.extractor.bo.cwm.db.Partition;
import com.pactera.edg.am.metamanager.extractor.bo.cwm.db.PrimaryKey;
import com.pactera.edg.am.metamanager.extractor.bo.cwm.db.Procedure;
import com.pactera.edg.am.metamanager.extractor.bo.cwm.db.SQLIndex;
import com.pactera.edg.am.metamanager.extractor.bo.cwm.db.Schema;
import com.pactera.edg.am.metamanager.extractor.bo.cwm.db.Table;
import com.pactera.edg.am.metamanager.extractor.bo.cwm.db.View;

/**
 * 实现公共DB元数据采集功能(JDBC方式+SQL方式)
 * 
 * @author XKS
 * @version 1.0 Date: 6 04, 2013
 */
public class GPExtractServiceImpl extends DBExtractBaseServiceImpl {

	private final static List<String> systemTableList = new ArrayList<String>(12);
	{
		systemTableList.add("pg_class");
		systemTableList.add("pg_tables");
		systemTableList.add("pg_namespace");
		systemTableList.add("pg_description");

		systemTableList.add("pg_description");
		systemTableList.add("pg_attribute");
		systemTableList.add("information_schema.columns");
		systemTableList.add("information_schema.key_column_usage");

		systemTableList.add("pg_index");
		systemTableList.add("INFORMATION_SCHEMA.views");
		systemTableList.add("INFORMATION_SCHEMA.routines");
		systemTableList.add("pg_depend");
		systemTableList.add("pg_rewrite");
	}

	private Log log = LogFactory.getLog(GPExtractServiceImpl.class);

	@Override
	protected String signOfNoPrivilege() {
		return "表或视图不存在";
	}

	@Override
	protected List<String> getSystemTableList() {
		return systemTableList;
	}

	@SuppressWarnings("unchecked")
	protected List<NamedColumnSet> getTables(String schName) throws SQLException {
		log.info("开始采集表信息...");
		String sql = "select NULL AS table_cat, "
					+" c.nspname  as table_schem, "//            --表所在schema
					+" b.relname as table_name,    "//           --表名,
					+" 'TABLE' AS table_type, "// --表类型
					+" t.tablespace as TABLESPACE_NAME , "// --表空间
					+" d.description  as REMARKS    "//    --表描述,
					+" from pg_class b   "
					+" join pg_tables t "
					+" on t.tablename = b.relname "
					+" join pg_namespace c  "
					+" on b.relnamespace=c.oid "
					+" left join pg_description d  "
					+" on d.objsubid=0 "
					+" and d.objoid = b.oid "
					+" where 0=0  "
					+" and c.nspname = ? "//    --输入模式名:一个gp数据库里有多个schema_name
					+" and b.relname not like 'tmp%'  "//   ---排除临时表
					+" and b.relkind = 'r' "
					+" and t.schemaname = c.nspname "
					+" order by b.relname ";
		return super.getJdbcTemplate().query(sql, new Object[] { schName }, new TableRowMapper());
	}

	protected void setPartitions(String schName, Map<String, Table> tableCache) throws SQLException {
		log.info("开始采集表分区信息...");
		String sql = "select schemaname TABLE_OWNER, "
					+" tablename as TABLE_NAME, "
					+" tablespace as TABLESPACE_NAME," 
					+" 'NO' COMPOSITE, "
					+" tablespace as PARTITION_NAME,"
					+" null as PARTITION_POSITION"
					+" from pg_tables a where a.schemaname = ? ";

		super.getJdbcTemplate().query(sql, new Object[] { schName }, new PartitionRowCallbackHandler(tableCache));
	}

	private class PartitionRowCallbackHandler implements RowCallbackHandler {

		private Map<String, Table> tableCache;

		public PartitionRowCallbackHandler(Map<String, Table> tableCache)
		{
			this.tableCache = tableCache;
		}

		public void processRow(ResultSet rs) throws SQLException {
			String tableName = rs.getString(Table.TABLE_NAME);
			if (tableCache.containsKey(tableName)) {
				Partition partition = new Partition();
				partition.setName(rs.getString(Partition.PARTITION_NAME));
				partition.setTableName(tableName);
				partition.addAttr(Partition.COMPOSITE, rs.getString(Partition.COMPOSITE));
				partition
						.addAttr(Partition.PARTITION_POSITION, String.valueOf(rs.getInt(Partition.PARTITION_POSITION)));
				partition.addAttr(Partition.TABLESPACE_NAME, rs.getString(Partition.TABLESPACE_NAME));

				tableCache.get(tableName).addPartition(partition);
			}

		}

	}

	/**
	 * 设置视图的SQL属性
	 * 
	 * @see com.pactera.edg.am.metamanager.extractor.adapter.extract.db.impl.DBExtractBaseServiceImpl#setViewText(java.lang.String,
	 *      java.util.Map)
	 */
	protected void setViewText(String schName, Map<String, View> viewCache) throws SQLException {
		String sql = "select  table_name as VIEW_NAME,view_definition AS sql FROM INFORMATION_SCHEMA.views WHERE table_schema = ? ";
		super.getJdbcTemplate().query(sql, new Object[] { schName }, new ViewRowCallbackHandler(viewCache));
	}

	private class ViewRowCallbackHandler implements RowCallbackHandler {
		private Map<String, View> viewCache;

		public ViewRowCallbackHandler(Map<String, View> viewCache)
		{
			this.viewCache = viewCache;
		}

		public void processRow(ResultSet rs) throws SQLException {
			String viewText = rs.getString("VIEW_NAME");
			if (viewCache.containsKey(viewText)) {
				viewCache.get(viewText).addAttr(View.SQL, rs.getString(View.SQL));
			}

		}

	}

	private class TableRowMapper implements RowMapper {

		public Object mapRow(ResultSet rs, int rowNum) throws SQLException {
			Table table = new Table();
			table.setName(rs.getString(Table.TABLE_NAME));
			// table.addAttr(Table.TABLE_TYPE, rs.getString(Table.TABLE_TYPE));

			table.addAttr(Table.TABLESPACE_NAME, rs.getString(Table.TABLESPACE_NAME));
			table.addAttr(ModelElement.REMARKS, rs.getString(ModelElement.REMARKS));

			table.setType(NamedColumnSetType.TABLE);

			return table;
		}

	}

	protected void setTableColumns(String schName, Map<String, Table> tableCache) throws SQLException {
		log.info("开始采集表的字段信息...");
		String sql = "select NULL AS table_cat,"
					+" c.nspname  as table_schem,"//            --表所在schema
					+" b.relname as table_name ,    "//         --表名
					+" a.attname as column_name,       "//      --字段名
					+" a.atttypid as data_type,"
					+" substring(pg_catalog.format_type(a.atttypid,a.atttypmod) from '^[^(]+') TYPE_NAME, "//  --字段数据类型
					+" (case when co.character_maximum_length is null then co.numeric_precision else co.character_maximum_length end) column_size,"
					+" 0 AS buffer_length,"
					+" co.numeric_scale as decimal_digits,"
					+" 10 AS num_prec_radix,"
					+" (case when co.is_nullable = 'YES' then '1' else '0'end)  as  nullable  ,"
					+" e.description  as remarks,   "//                                 --字段注释
					+" null as column_def,"
					+" 0 AS sql_data_type,"
					+" 0 AS sql_datetime_sub,"
					+" (case when co.character_maximum_length is null then co.numeric_precision else co.character_maximum_length end) char_octet_length,"
					+" co.ordinal_position,"
					+" co.is_nullable"
					+" from pg_attribute a "
					+" join pg_class b "
					+" on a.attrelid =b.oid"
					+" join pg_namespace c "
					+" on b.relnamespace=c.oid"
					+" left join pg_description d "
					+" on a.attrelid = d.objoid"
					+" and d.objsubid=0"
					+" left join pg_description e "
					+" on a.attrelid = e.objoid"
					+" and a.attnum  = e.objsubid"
					+" left join information_schema.columns co"
					+" on co.table_schema = c.nspname"
					+" and co.table_name = b.relname"
					+" and a.attname = co.column_name"
					+" where 0=0 "
					+" and c.nspname = ? "//   --输入模式名:一个gp数据库里有多个schema_name
					+" and a.attnum>0 and not a.attisdropped "
					+" and b.relname not like 'tmp%' "//   ---排除临时表
					+" and b.relkind = 'r'"
					+" order by b.relname";
		super.getJdbcTemplate().query(sql, new Object[] { schName }, new ColumnRowCallbackHandler(tableCache));
	}

	private class ColumnRowCallbackHandler implements RowCallbackHandler {

		private Map<String, Table> tableCache;

		public ColumnRowCallbackHandler(Map<String, Table> tableCache)
		{
			this.tableCache = tableCache;
		}

		public void processRow(ResultSet rs) throws SQLException {
			String tableName = rs.getString(Table.TABLE_NAME);
			if (!tableCache.containsKey(tableName)) { return; }

			String colName = rs.getString(Column.COLUMN_NAME);
			Column column = new Column();
			column.setName(colName);
			String typeName = rs.getString(Column.TYPE_NAME);
			if (typeName != null && typeName.indexOf("CHAR") >= 0) {
				typeName = typeName.substring(0, typeName.indexOf("CHAR") + "CHAR".length());
			}
			// column.addAttr(Column.TYPE_NAME, typeName);
			// column.addAttr(Column.COLUMN_DEF,
			// rs.getString(Column.COLUMN_DEF));
			column.setTypeName(typeName);
			column.setColumnDef(rs.getString(Column.COLUMN_DEF));
			column.addAttr(ModelElement.REMARKS, rs.getString(ModelElement.REMARKS));
			column.setDataType(rs.getInt(Column.DATA_TYPE));
			column.setColumnSize(rs.getInt(Column.COLUMN_SIZE));
			column.setBufferLength(rs.getInt(Column.BUFFER_LENGTH));
			column.setDecimalDigits(rs.getInt(Column.DECIMAL_DIGITS));
			column.setNumPrecRadix(rs.getInt(Column.NUM_PREC_RADIX));
			column.setSqlDataType(rs.getInt(Column.SQL_DATA_TYPE));
			column.setSqlDatetimeSub(rs.getInt(Column.SQL_DATETIME_SUB));
			column.setCharOctetLength(rs.getInt(Column.CHAR_OCTET_LENGTH));
			column.setOrdinalPosition(rs.getInt(Column.ORDINAL_POSITION));
			String nullable = rs.getString(Column.IS_NULLABLE);
			if ("YES".equals(nullable)) {
				// 可为空
				column.setNullable(true);
			}

			Table table = tableCache.get(tableName);
			table.addColumn(column);

		}

	}

	/**
	 * 获取表的主键
	 * 
	 * @param schName
	 * @param tableCache
	 */
	protected void setPrimaryKeies(String schName, Map<String, Table> tableCache) throws SQLException {
		log.info("开始采集主键信息...");
		String sql = " select NULL AS table_cat,"
					+" table_schema as table_schem,"
					+" table_name,"
					+" column_name,"
					+" ordinal_position AS key_seq,"
					+" constraint_name pk_name"
					+" from information_schema.key_column_usage"
					+" where table_schema=?  and position_in_unique_constraint is null  ";

		super.getJdbcTemplate().query(sql, new Object[] { schName }, new PrimaryKeyRowCallbackHandler(tableCache));
	}

	private class PrimaryKeyRowCallbackHandler implements RowCallbackHandler {

		private Map<String, Table> tableCache;

		public PrimaryKeyRowCallbackHandler(Map<String, Table> tableCache)
		{
			this.tableCache = tableCache;
		}

		public void processRow(ResultSet rs) throws SQLException {
			String tableName = rs.getString(Table.TABLE_NAME);

			if (tableCache.containsKey(tableName)) {

				PrimaryKey primaryKey = new PrimaryKey();
				primaryKey.setName(rs.getString(PrimaryKey.PK_NAME));
				primaryKey.setColumnName(rs.getString(Column.COLUMN_NAME));
				primaryKey.addAttr(PrimaryKey.KEY_SEQ, String.valueOf(rs.getInt(PrimaryKey.KEY_SEQ)));

				Table table = tableCache.get(tableName);
				table.addPrimaryKey(primaryKey);

			}

		}

	}

	@SuppressWarnings("unchecked")
	protected List<SQLIndex> getIndexs(String schName) throws SQLException {
		log.info("开始采集索引信息...");
		String sql = "select null as table_cat,"
					+" n.nspname as table_schem,"
					+" c.relname as table_name,"
					+" (case when x.indisunique = true then '0' else '1' end) as  non_unique, "
					+" null as index_qualifier,"
					+" i.relname as index_name,"
					+" 1 type,"
					+" co.ordinal_position ,"
					+" a.attname as column_name,"
					+" null asc_or_desc,"
					+" 0 cardinality,"
					+" i.relpages as pages,"
					+" null filter_condition"
					+" from pg_index x"
					+" left join pg_class c on c.oid = x.indrelid"
					+" left join pg_class i on i.oid = x.indexrelid"
					+" left join pg_namespace n on n.oid = c.relnamespace"
					+" left join pg_attribute a ON a.attrelid = i.oid AND a.attnum = ANY(indkey)"
					+" left join information_schema.columns co on co.table_schema = n.nspname  and co.table_name = c.relname and co.column_name = a.attname "
					+" where c.relkind = 'r' and i.relkind = 'i'"
					+" and n.nspname = ? ";
		return super.getJdbcTemplate().query(sql, new Object[] { schName }, new IndexRowMapper());
	}

	private class IndexRowMapper implements RowMapper {

		public Object mapRow(ResultSet rs, int rowNum) throws SQLException {
			SQLIndex sIndex = new SQLIndex();
			sIndex.setName(rs.getString(SQLIndex.INDEX_NAME));
			sIndex.setTableName(rs.getString(Table.TABLE_NAME));
			sIndex.setColumnName(rs.getString(Column.COLUMN_NAME));

			int nonUnique = rs.getInt(SQLIndex.NON_UNIQUE);
			if (nonUnique == 0) {
				sIndex.addAttr(SQLIndex.NON_UNIQUE, "UNIQUE");
			}
			else {
				sIndex.addAttr(SQLIndex.NON_UNIQUE, "NON_UNIQUE");
			}
			sIndex.addAttr(SQLIndex.ORDINAL_POSITION, String.valueOf(rs.getInt(SQLIndex.ORDINAL_POSITION)));
			sIndex.addAttr(SQLIndex.CARDINALITY, String.valueOf(rs.getInt(SQLIndex.CARDINALITY)));
			sIndex.addAttr(SQLIndex.PAGES, String.valueOf(rs.getInt(SQLIndex.PAGES)));

			return sIndex;
		}

	}

	@SuppressWarnings("unchecked")
	protected List<NamedColumnSet> getViews(String schName) throws SQLException {
		log.info("开始采集GP视图信息...");
		// 从DBA*表采集视图
		String sql = "select NULL AS table_cat,"
					+" table_schema as table_schem,"
					+" table_name ,"
					+" 'VIEW'  table_type,"
					+" null AS remarks"
					+" FROM INFORMATION_SCHEMA.views"
					+" WHERE table_schema = ? ";
		return super.getJdbcTemplate().query(sql, new Object[] { schName }, new ViewRowMapper());
	}

	private class ViewRowMapper implements RowMapper {

		public Object mapRow(ResultSet rs, int rowNum) throws SQLException {
			View view = new View();
			view.setName(rs.getString(Table.TABLE_NAME));
			// table.addAttr(Table.TABLE_TYPE, rs.getString(Table.TABLE_TYPE));

			// table.addAttr(Table.TABLESPACE_NAME,
			// rs.getString(Table.TABLESPACE_NAME));
			view.addAttr(ModelElement.REMARKS, rs.getString(ModelElement.REMARKS));

			view.setType(NamedColumnSetType.VIEW);

			return view;
		}

	}

	protected void setProceduresText(String schName, Map<String, Procedure> procedureCache) throws SQLException {

		String sql = "select routine_name AS PROCEDURE_NAME,routine_definition TEXT FROM INFORMATION_SCHEMA.routines WHERE routine_schema = ? ";
		super.getJdbcTemplate().query(sql, new Object[] { schName }, new ProcedureRowCallbackHandler(procedureCache));
	}

	private class ProcedureRowCallbackHandler implements RowCallbackHandler {

		private Map<String, Procedure> procedureCache;

		public ProcedureRowCallbackHandler(Map<String, Procedure> procedureCache)
		{
			this.procedureCache = procedureCache;
		}

		public void processRow(ResultSet rs) throws SQLException {
			String procName = rs.getString(Procedure.PROCEDURE_NAME);
			if (procedureCache.containsKey(procName)) {
				// 找到存储过程的内容
				procedureCache.get(procName).setText(rs.getString(Procedure.TEXT));
			}

		}

	}

	/**
	 * 建立视图与视图间,视图与表间的依赖关系
	 * 
	 * @see com.pactera.edg.am.metamanager.extractor.adapter.extract.db.impl.DBExtractBaseServiceImpl#setViewDependency(java.lang.String,
	 *      java.util.Map)
	 */
	protected void setViewDependency(String schName, Map<String, View> viewCache) throws SQLException {
		String sql = "select  n.nspname SCH_NAME ,"
					+" (select a.relname as tabname from pg_class a where a.oid=pc.oid) table_name ,"
					+" (select a.relname as tabname from pg_class a where a.oid=c.ev_class) as  name,"
					+" ("
					+" CASE"
					+" WHEN (pc.relkind = 'r')"
					+" THEN 'TABLE'"
					+" WHEN (pc.relkind = 'v')"
					+" THEN 'VIEW'"
					+" ELSE NULL"
					+" END) REFERENCED_TYPE"
					+" from pg_depend a,pg_depend b,pg_class pc,pg_rewrite c ,pg_namespace n"
					+" where a.refclassid=1259        "
					+" and b.deptype='i'  "
					+" and a.classid=2618  "
					+" and a.objid=b.objid  "
					+" and a.classid=b.classid  "
					+" and a.refclassid=b.refclassid  "
					+" and a.refobjid<>b.refobjid  "
					+" and pc.oid=a.refobjid     "  
					+" and c.oid=b.objid   "
					+" and pc.relnamespace = n.oid"
					+" and n.nspname  = ? "
					+" group by c.ev_class,pc.oid,n.nspname ,pc.relkind";

		super.getJdbcTemplate().query(sql, new Object[] { schName }, new ViewReferenceRowCallbackHandler(viewCache));
	}

	private class ViewReferenceRowCallbackHandler implements RowCallbackHandler {

		private Map<String, View> viewCache;

		public ViewReferenceRowCallbackHandler(Map<String, View> viewCache)
		{
			this.viewCache = viewCache;
		}

		public void processRow(ResultSet rs) throws SQLException {
			String viewName = rs.getString("NAME");
			if (viewCache.containsKey(viewName)) {
				// 包含于视图缓存中
				String tabName = rs.getString(Table.TABLE_NAME);
				String schName = rs.getString(Schema.SCH_NAME).toLowerCase();
				String type = rs.getString(View.REFERENCED_TYPE);
				if (NamedColumnSetType.TABLE.toString().equals(type)) {
					viewCache.get(viewName).addReferenceSchTable(schName.concat(".").concat(tabName),
							NamedColumnSetType.TABLE);
				}
				else if (NamedColumnSetType.VIEW.toString().equals(type)) {
					viewCache.get(viewName).addReferenceSchTable(schName.concat(".").concat(tabName),
							NamedColumnSetType.VIEW);
				}
				// log.info("视图:" + viewName + "依赖的表:" +
				// schName.concat(".").concat(tabName));

			}

		}

	}
}
