/*
 * Copyright 2004-2020 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.table;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

import org.h2.api.IntervalQualifier;
import org.h2.api.Trigger;
import org.h2.command.Command;
import org.h2.command.Parser;
import org.h2.constraint.Constraint;
import org.h2.constraint.Constraint.Type;
import org.h2.constraint.ConstraintCheck;
import org.h2.constraint.ConstraintDomain;
import org.h2.constraint.ConstraintReferential;
import org.h2.constraint.ConstraintUnique;
import org.h2.engine.Constants;
import org.h2.engine.DbObject;
import org.h2.engine.QueryStatisticsData;
import org.h2.engine.Right;
import org.h2.engine.Role;
import org.h2.engine.SessionLocal;
import org.h2.engine.SessionLocal.State;
import org.h2.engine.Setting;
import org.h2.engine.User;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionVisitor;
import org.h2.expression.ValueExpression;
import org.h2.index.Index;
import org.h2.index.MetaIndex;
import org.h2.message.DbException;
import org.h2.mvstore.FileStore;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.db.Store;
import org.h2.pagestore.PageStore;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.schema.Constant;
import org.h2.schema.Domain;
import org.h2.schema.FunctionAlias;
import org.h2.schema.Schema;
import org.h2.schema.Sequence;
import org.h2.schema.TriggerObject;
import org.h2.schema.UserAggregate;
import org.h2.schema.FunctionAlias.JavaMethod;
import org.h2.store.InDoubtTransaction;
import org.h2.util.DateTimeUtils;
import org.h2.util.MathUtils;
import org.h2.util.NetworkConnectionInfo;
import org.h2.util.StringUtils;
import org.h2.util.TimeZoneProvider;
import org.h2.util.Utils;
import org.h2.util.geometry.EWKTUtils;
import org.h2.value.CompareMode;
import org.h2.value.DataType;
import org.h2.value.ExtTypeInfoEnum;
import org.h2.value.ExtTypeInfoGeometry;
import org.h2.value.ExtTypeInfoRow;
import org.h2.value.TypeInfo;
import org.h2.value.Value;
import org.h2.value.ValueBigint;
import org.h2.value.ValueBoolean;
import org.h2.value.ValueDouble;
import org.h2.value.ValueInteger;
import org.h2.value.ValueNull;
import org.h2.value.ValueToObjectConverter2;
import org.h2.value.ValueVarchar;

/**
 * This class is responsible to build the INFORMATION_SCHEMA tables.
 */
public final class InformationSchemaTable extends MetaTable {

    private static final String CHARACTER_SET_NAME = "Unicode";

    // Standard table

    private static final int INFORMATION_SCHEMA_CATALOG_NAME = 0;

    // Standard views

    private static final int CHECK_CONSTRAINTS = INFORMATION_SCHEMA_CATALOG_NAME + 1;

    private static final int COLLATIONS = CHECK_CONSTRAINTS + 1;

    private static final int COLUMNS = COLLATIONS + 1;

    private static final int COLUMN_PRIVILEGES = COLUMNS + 1;

    private static final int CONSTRAINT_COLUMN_USAGE = COLUMN_PRIVILEGES + 1;

    private static final int DOMAINS = CONSTRAINT_COLUMN_USAGE + 1;

    private static final int DOMAIN_CONSTRAINTS = DOMAINS + 1;

    private static final int ELEMENT_TYPES = DOMAIN_CONSTRAINTS + 1;

    private static final int FIELDS = ELEMENT_TYPES + 1;

    private static final int KEY_COLUMN_USAGE = FIELDS + 1;

    private static final int PARAMETERS = KEY_COLUMN_USAGE + 1;

    private static final int REFERENTIAL_CONSTRAINTS = PARAMETERS + 1;

    private static final int ROUTINES = REFERENTIAL_CONSTRAINTS + 1;

    private static final int SCHEMATA = ROUTINES + 1;

    private static final int SEQUENCES = SCHEMATA + 1;

    private static final int TABLES = SEQUENCES + 1;

    private static final int TABLE_CONSTRAINTS = TABLES + 1;

    private static final int TABLE_PRIVILEGES = TABLE_CONSTRAINTS + 1;

    private static final int TRIGGERS = TABLE_PRIVILEGES + 1;

    private static final int VIEWS = TRIGGERS + 1;

    // Extensions

    private static final int CONSTANTS = VIEWS + 1;

    private static final int ENUM_VALUES = CONSTANTS + 1;

    private static final int INDEXES = ENUM_VALUES + 1;

    private static final int INDEX_COLUMNS = INDEXES + 1;

    private static final int IN_DOUBT = INDEX_COLUMNS + 1;

    private static final int LOCKS = IN_DOUBT + 1;

    private static final int QUERY_STATISTICS = LOCKS + 1;

    private static final int RIGHTS = QUERY_STATISTICS + 1;

    private static final int ROLES = RIGHTS + 1;

    private static final int SESSIONS = ROLES + 1;

    private static final int SESSION_STATE = SESSIONS + 1;

    private static final int SETTINGS = SESSION_STATE + 1;

    private static final int SYNONYMS = SETTINGS + 1;

    private static final int USERS = SYNONYMS + 1;

    /**
     * The number of meta table types. Supported meta table types are
     * {@code 0..META_TABLE_TYPE_COUNT - 1}.
     */
    public static final int META_TABLE_TYPE_COUNT = USERS + 1;

    private final boolean isView;

    /**
     * Create a new metadata table.
     *
     * @param schema the schema
     * @param id the object id
     * @param type the meta table type
     */
    public InformationSchemaTable(Schema schema, int id, int type) {
        super(schema, id, type);
        Column[] cols;
        String indexColumnName = null;
        boolean isView = true;
        switch (type) {
        // Standard table
        case INFORMATION_SCHEMA_CATALOG_NAME:
            setMetaTableName("INFORMATION_SCHEMA_CATALOG_NAME");
            isView = false;
            cols = createColumns(
                    "CATALOG_NAME"
            );
            break;
        // Standard views
        case CHECK_CONSTRAINTS:
            setMetaTableName("CHECK_CONSTRAINTS");
            cols = createColumns(
                    "CONSTRAINT_CATALOG",
                    "CONSTRAINT_SCHEMA",
                    "CONSTRAINT_NAME",
                    "CHECK_CLAUSE"
            );
            indexColumnName = "CONSTRAINT_NAME";
            break;
        case COLLATIONS:
            setMetaTableName("COLLATIONS");
            cols = createColumns(
                    "COLLATION_CATALOG",
                    "COLLATION_SCHEMA",
                    "COLLATION_NAME",
                    "PAD_ATTRIBUTE",
                    // extensions
                    "LANGUAGE_TAG"
            );
            break;
        case COLUMNS:
            setMetaTableName("COLUMNS");
            cols = createColumns(
                    "TABLE_CATALOG",
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "COLUMN_NAME",
                    "ORDINAL_POSITION INT",
                    "COLUMN_DEFAULT",
                    "IS_NULLABLE",
                    "DATA_TYPE",
                    "CHARACTER_MAXIMUM_LENGTH BIGINT",
                    "CHARACTER_OCTET_LENGTH BIGINT",
                    "NUMERIC_PRECISION INT",
                    "NUMERIC_PRECISION_RADIX INT",
                    "NUMERIC_SCALE INT",
                    "DATETIME_PRECISION INT",
                    "INTERVAL_TYPE",
                    "INTERVAL_PRECISION INT",
                    "CHARACTER_SET_CATALOG",
                    "CHARACTER_SET_SCHEMA",
                    "CHARACTER_SET_NAME",
                    "COLLATION_CATALOG",
                    "COLLATION_SCHEMA",
                    "COLLATION_NAME",
                    "DOMAIN_CATALOG",
                    "DOMAIN_SCHEMA",
                    "DOMAIN_NAME",
                    "MAXIMUM_CARDINALITY INT",
                    "DTD_IDENTIFIER",
                    "IS_IDENTITY",
                    "IDENTITY_GENERATION",
                    "IDENTITY_START BIGINT",
                    "IDENTITY_INCREMENT BIGINT",
                    "IDENTITY_MAXIMUM BIGINT",
                    "IDENTITY_MINIMUM BIGINT",
                    "IDENTITY_CYCLE",
                    "IS_GENERATED",
                    "GENERATION_EXPRESSION",
                    "DECLARED_DATA_TYPE",
                    "DECLARED_NUMERIC_PRECISION INT",
                    "DECLARED_NUMERIC_SCALE INT",
                    // extensions
                    "GEOMETRY_TYPE",
                    "GEOMETRY_SRID INT",
                    "IDENTITY_BASE BIGINT",
                    "IDENTITY_CACHE BIGINT",
                    "COLUMN_ON_UPDATE",
                    "IS_VISIBLE BOOLEAN",
                    "DEFAULT_ON_NULL BOOLEAN",
                    "SELECTIVITY INT",
                    "REMARKS"
            );
            indexColumnName = "TABLE_NAME";
            break;
        case COLUMN_PRIVILEGES:
            setMetaTableName("COLUMN_PRIVILEGES");
            cols = createColumns(
                    "GRANTOR",
                    "GRANTEE",
                    "TABLE_CATALOG",
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "COLUMN_NAME",
                    "PRIVILEGE_TYPE",
                    "IS_GRANTABLE"
            );
            indexColumnName = "TABLE_NAME";
            break;
        case CONSTRAINT_COLUMN_USAGE:
            setMetaTableName("CONSTRAINT_COLUMN_USAGE");
            cols = createColumns(
                    "TABLE_CATALOG",
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "COLUMN_NAME",
                    "CONSTRAINT_CATALOG",
                    "CONSTRAINT_SCHEMA",
                    "CONSTRAINT_NAME"
            );
            indexColumnName = "TABLE_NAME";
            break;
        case DOMAINS:
            setMetaTableName("DOMAINS");
            cols = createColumns(
                    "DOMAIN_CATALOG",
                    "DOMAIN_SCHEMA",
                    "DOMAIN_NAME",
                    "DATA_TYPE",
                    "CHARACTER_MAXIMUM_LENGTH BIGINT",
                    "CHARACTER_OCTET_LENGTH BIGINT",
                    "CHARACTER_SET_CATALOG",
                    "CHARACTER_SET_SCHEMA",
                    "CHARACTER_SET_NAME",
                    "COLLATION_CATALOG",
                    "COLLATION_SCHEMA",
                    "COLLATION_NAME",
                    "NUMERIC_PRECISION INT",
                    "NUMERIC_PRECISION_RADIX INT",
                    "NUMERIC_SCALE INT",
                    "DATETIME_PRECISION INT",
                    "INTERVAL_TYPE",
                    "INTERVAL_PRECISION INT",
                    "DOMAIN_DEFAULT",
                    "MAXIMUM_CARDINALITY INT",
                    "DTD_IDENTIFIER",
                    "DECLARED_DATA_TYPE",
                    "DECLARED_NUMERIC_PRECISION INT",
                    "DECLARED_NUMERIC_SCALE INT",
                    // extensions
                    "GEOMETRY_TYPE",
                    "GEOMETRY_SRID INT",
                    "DOMAIN_ON_UPDATE",
                    "PARENT_DOMAIN_CATALOG",
                    "PARENT_DOMAIN_SCHEMA",
                    "PARENT_DOMAIN_NAME",
                    "REMARKS"
            );
            indexColumnName = "DOMAIN_NAME";
            break;
        case DOMAIN_CONSTRAINTS:
            setMetaTableName("DOMAIN_CONSTRAINTS");
            cols = createColumns(
                    "CONSTRAINT_CATALOG",
                    "CONSTRAINT_SCHEMA",
                    "CONSTRAINT_NAME",
                    "DOMAIN_CATALOG",
                    "DOMAIN_SCHEMA",
                    "DOMAIN_NAME",
                    "IS_DEFERRABLE",
                    "INITIALLY_DEFERRED",
                    // extensions
                    "REMARKS"
            );
            indexColumnName = "DOMAIN_NAME";
            break;
        case ELEMENT_TYPES:
            setMetaTableName("ELEMENT_TYPES");
            cols = createColumns(
                    "OBJECT_CATALOG",
                    "OBJECT_SCHEMA",
                    "OBJECT_NAME",
                    "OBJECT_TYPE",
                    "COLLECTION_TYPE_IDENTIFIER",
                    "DATA_TYPE",
                    "CHARACTER_MAXIMUM_LENGTH",
                    "CHARACTER_OCTET_LENGTH",
                    "CHARACTER_SET_CATALOG",
                    "CHARACTER_SET_SCHEMA",
                    "CHARACTER_SET_NAME",
                    "COLLATION_CATALOG",
                    "COLLATION_SCHEMA",
                    "COLLATION_NAME",
                    "NUMERIC_PRECISION",
                    "NUMERIC_PRECISION_RADIX",
                    "NUMERIC_SCALE",
                    "DATETIME_PRECISION",
                    "INTERVAL_TYPE",
                    "INTERVAL_PRECISION",
                    "MAXIMUM_CARDINALITY",
                    "DTD_IDENTIFIER",
                    "DECLARED_DATA_TYPE",
                    "DECLARED_NUMERIC_PRECISION INT",
                    "DECLARED_NUMERIC_SCALE INT",
                    // extensions
                    "GEOMETRY_TYPE",
                    "GEOMETRY_SRID INT"
            );
            break;
        case FIELDS:
            setMetaTableName("FIELDS");
            cols = createColumns(
                    "OBJECT_CATALOG",
                    "OBJECT_SCHEMA",
                    "OBJECT_NAME",
                    "OBJECT_TYPE",
                    "ROW_IDENTIFIER",
                    "FIELD_NAME",
                    "ORDINAL_POSITION",
                    "DATA_TYPE",
                    "CHARACTER_MAXIMUM_LENGTH",
                    "CHARACTER_OCTET_LENGTH",
                    "CHARACTER_SET_CATALOG",
                    "CHARACTER_SET_SCHEMA",
                    "CHARACTER_SET_NAME",
                    "COLLATION_CATALOG",
                    "COLLATION_SCHEMA",
                    "COLLATION_NAME",
                    "NUMERIC_PRECISION",
                    "NUMERIC_PRECISION_RADIX",
                    "NUMERIC_SCALE",
                    "DATETIME_PRECISION",
                    "INTERVAL_TYPE",
                    "INTERVAL_PRECISION",
                    "MAXIMUM_CARDINALITY",
                    "DTD_IDENTIFIER",
                    "DECLARED_DATA_TYPE",
                    "DECLARED_NUMERIC_PRECISION INT",
                    "DECLARED_NUMERIC_SCALE INT",
                    // extensions
                    "GEOMETRY_TYPE",
                    "GEOMETRY_SRID INT"
            );
            break;
        case KEY_COLUMN_USAGE:
            setMetaTableName("KEY_COLUMN_USAGE");
            cols = createColumns(
                    "CONSTRAINT_CATALOG",
                    "CONSTRAINT_SCHEMA",
                    "CONSTRAINT_NAME",
                    "TABLE_CATALOG",
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "COLUMN_NAME",
                    "ORDINAL_POSITION INT",
                    "POSITION_IN_UNIQUE_CONSTRAINT INT"
            );
            indexColumnName = "TABLE_NAME";
            break;
        case PARAMETERS:
            setMetaTableName("PARAMETERS");
            cols = createColumns(
                    "SPECIFIC_CATALOG",
                    "SPECIFIC_SCHEMA",
                    "SPECIFIC_NAME",
                    "ORDINAL_POSITION",
                    "PARAMETER_MODE",
                    "IS_RESULT",
                    "AS_LOCATOR",
                    "PARAMETER_NAME",
                    "DATA_TYPE",
                    "CHARACTER_MAXIMUM_LENGTH BIGINT",
                    "CHARACTER_OCTET_LENGTH BIGINT",
                    "CHARACTER_SET_CATALOG",
                    "CHARACTER_SET_SCHEMA",
                    "CHARACTER_SET_NAME",
                    "COLLATION_CATALOG",
                    "COLLATION_SCHEMA",
                    "COLLATION_NAME",
                    "NUMERIC_PRECISION INT",
                    "NUMERIC_PRECISION_RADIX INT",
                    "NUMERIC_SCALE INT",
                    "DATETIME_PRECISION INT",
                    "INTERVAL_TYPE",
                    "INTERVAL_PRECISION INT",
                    "MAXIMUM_CARDINALITY INT",
                    "DTD_IDENTIFIER",
                    "DECLARED_DATA_TYPE",
                    "DECLARED_NUMERIC_PRECISION INT",
                    "DECLARED_NUMERIC_SCALE INT",
                    "PARAMETER_DEFAULT",
                    // extensions
                    "GEOMETRY_TYPE",
                    "GEOMETRY_SRID INT"
            );
            break;
        case REFERENTIAL_CONSTRAINTS:
            setMetaTableName("REFERENTIAL_CONSTRAINTS");
            cols = createColumns(
                    "CONSTRAINT_CATALOG",
                    "CONSTRAINT_SCHEMA",
                    "CONSTRAINT_NAME",
                    "UNIQUE_CONSTRAINT_CATALOG",
                    "UNIQUE_CONSTRAINT_SCHEMA",
                    "UNIQUE_CONSTRAINT_NAME",
                    "MATCH_OPTION",
                    "UPDATE_RULE",
                    "DELETE_RULE"
            );
            indexColumnName = "CONSTRAINT_NAME";
            break;
        case ROUTINES:
            setMetaTableName("ROUTINES");
            cols = createColumns(
                    "SPECIFIC_CATALOG",
                    "SPECIFIC_SCHEMA",
                    "SPECIFIC_NAME",
                    "ROUTINE_CATALOG",
                    "ROUTINE_SCHEMA",
                    "ROUTINE_NAME",
                    "ROUTINE_TYPE",
                    "DATA_TYPE",
                    "CHARACTER_MAXIMUM_LENGTH BIGINT",
                    "CHARACTER_OCTET_LENGTH BIGINT",
                    "CHARACTER_SET_CATALOG",
                    "CHARACTER_SET_SCHEMA",
                    "CHARACTER_SET_NAME",
                    "COLLATION_CATALOG",
                    "COLLATION_SCHEMA",
                    "COLLATION_NAME",
                    "NUMERIC_PRECISION INT",
                    "NUMERIC_PRECISION_RADIX INT",
                    "NUMERIC_SCALE INT",
                    "DATETIME_PRECISION INT",
                    "INTERVAL_TYPE",
                    "INTERVAL_PRECISION INT",
                    "MAXIMUM_CARDINALITY INT",
                    "DTD_IDENTIFIER",
                    "ROUTINE_BODY",
                    "ROUTINE_DEFINITION",
                    "EXTERNAL_NAME",
                    "EXTERNAL_LANGUAGE",
                    "PARAMETER_STYLE",
                    "IS_DETERMINISTIC",
                    "DECLARED_DATA_TYPE",
                    "DECLARED_NUMERIC_PRECISION INT",
                    "DECLARED_NUMERIC_SCALE INT",
                    // extensions
                    "GEOMETRY_TYPE",
                    "GEOMETRY_SRID INT",
                    "REMARKS"
            );
            break;
        case SCHEMATA:
            setMetaTableName("SCHEMATA");
            cols = createColumns(
                    "CATALOG_NAME",
                    "SCHEMA_NAME",
                    "SCHEMA_OWNER",
                    "DEFAULT_CHARACTER_SET_CATALOG",
                    "DEFAULT_CHARACTER_SET_SCHEMA",
                    "DEFAULT_CHARACTER_SET_NAME",
                    "SQL_PATH",
                    // extensions
                    "DEFAULT_COLLATION_NAME", // MySQL
                    "REMARKS"
            );
            break;
        case SEQUENCES:
            setMetaTableName("SEQUENCES");
            cols = createColumns(
                    "SEQUENCE_CATALOG",
                    "SEQUENCE_SCHEMA",
                    "SEQUENCE_NAME",
                    "DATA_TYPE",
                    "NUMERIC_PRECISION INT",
                    "NUMERIC_PRECISION_RADIX INT",
                    "NUMERIC_SCALE INT",
                    "START_VALUE BIGINT",
                    "MINIMUM_VALUE BIGINT",
                    "MAXIMUM_VALUE BIGINT",
                    "INCREMENT BIGINT",
                    "CYCLE_OPTION",
                    "DECLARED_DATA_TYPE",
                    "DECLARED_NUMERIC_PRECISION INT",
                    "DECLARED_NUMERIC_SCALE INT",
                    // extensions
                    "BASE_VALUE BIGINT",
                    "CACHE BIGINT",
                    "REMARKS"
            );
            indexColumnName = "SEQUENCE_NAME";
            break;
        case TABLES:
            setMetaTableName("TABLES");
            cols = createColumns(
                    "TABLE_CATALOG",
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "TABLE_TYPE",
                    "IS_INSERTABLE_INTO",
                    "COMMIT_ACTION",
                    // extensions
                    "STORAGE_TYPE",
                    "REMARKS",
                    "LAST_MODIFICATION BIGINT",
                    "TABLE_CLASS",
                    "ROW_COUNT_ESTIMATE BIGINT"
            );
            indexColumnName = "TABLE_NAME";
            break;
        case TABLE_CONSTRAINTS:
            setMetaTableName("TABLE_CONSTRAINTS");
            cols = createColumns(
                    "CONSTRAINT_CATALOG",
                    "CONSTRAINT_SCHEMA",
                    "CONSTRAINT_NAME",
                    "CONSTRAINT_TYPE",
                    "TABLE_CATALOG",
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "IS_DEFERRABLE",
                    "INITIALLY_DEFERRED",
                    "ENFORCED",
                    // extensions
                    "INDEX_CATALOG",
                    "INDEX_SCHEMA",
                    "INDEX_NAME",
                    "REMARKS"
            );
            indexColumnName = "TABLE_NAME";
            break;
        case TABLE_PRIVILEGES:
            setMetaTableName("TABLE_PRIVILEGES");
            cols = createColumns(
                    "GRANTOR",
                    "GRANTEE",
                    "TABLE_CATALOG",
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "PRIVILEGE_TYPE",
                    "IS_GRANTABLE",
                    "WITH_HIERARCHY"
            );
            indexColumnName = "TABLE_NAME";
            break;
        case TRIGGERS:
            setMetaTableName("TRIGGERS");
            cols = createColumns(
                    "TRIGGER_CATALOG",
                    "TRIGGER_SCHEMA",
                    "TRIGGER_NAME",
                    // extensions
                    "TRIGGER_TYPE",
                    "TABLE_CATALOG",
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "BEFORE BIT",
                    "JAVA_CLASS",
                    "QUEUE_SIZE INT",
                    "NO_WAIT BIT",
                    "REMARKS"
            );
            indexColumnName = "TABLE_NAME";
            break;
        case VIEWS:
            setMetaTableName("VIEWS");
            cols = createColumns(
                    "TABLE_CATALOG",
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "VIEW_DEFINITION",
                    "CHECK_OPTION",
                    "IS_UPDATABLE",
                    "INSERTABLE_INTO",
                    "IS_TRIGGER_UPDATABLE",
                    "IS_TRIGGER_DELETABLE",
                    "IS_TRIGGER_INSERTABLE_INTO",
                    // extensions
                    "STATUS",
                    "REMARKS"
            );
            indexColumnName = "TABLE_NAME";
            break;
        // Extensions
        case CONSTANTS:
            setMetaTableName("CONSTANTS");
            isView = false;
            cols = createColumns(
                    "CONSTANT_CATALOG",
                    "CONSTANT_SCHEMA",
                    "CONSTANT_NAME",
                    "VALUE_DEFINITION",
                    "DATA_TYPE",
                    "CHARACTER_MAXIMUM_LENGTH BIGINT",
                    "CHARACTER_OCTET_LENGTH BIGINT",
                    "CHARACTER_SET_CATALOG",
                    "CHARACTER_SET_SCHEMA",
                    "CHARACTER_SET_NAME",
                    "COLLATION_CATALOG",
                    "COLLATION_SCHEMA",
                    "COLLATION_NAME",
                    "NUMERIC_PRECISION INT",
                    "NUMERIC_PRECISION_RADIX INT",
                    "NUMERIC_SCALE INT",
                    "DATETIME_PRECISION INT",
                    "INTERVAL_TYPE",
                    "INTERVAL_PRECISION INT",
                    "MAXIMUM_CARDINALITY INT",
                    "DTD_IDENTIFIER",
                    "DECLARED_DATA_TYPE",
                    "DECLARED_NUMERIC_PRECISION INT",
                    "DECLARED_NUMERIC_SCALE INT",
                    "GEOMETRY_TYPE",
                    "GEOMETRY_SRID INT",
                    "REMARKS"
            );
            indexColumnName = "CONSTANT_NAME";
            break;
        case ENUM_VALUES:
            setMetaTableName("ENUM_VALUES");
            isView = false;
            cols = createColumns(
                    "OBJECT_CATALOG",
                    "OBJECT_SCHEMA",
                    "OBJECT_NAME",
                    "OBJECT_TYPE",
                    "ENUM_IDENTIFIER",
                    "VALUE_NAME",
                    "VALUE_ORDINAL"
            );
            break;
        case INDEXES:
            setMetaTableName("INDEXES");
            isView = false;
            cols = createColumns(
                    "INDEX_CATALOG",
                    "INDEX_SCHEMA",
                    "INDEX_NAME",
                    "TABLE_CATALOG",
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "INDEX_TYPE_NAME",
                    "IS_GENERATED BOOLEAN",
                    "REMARKS",
                    "INDEX_CLASS"
            );
            indexColumnName = "TABLE_NAME";
            break;
        case INDEX_COLUMNS:
            setMetaTableName("INDEX_COLUMNS");
            isView = false;
            cols = createColumns(
                    "INDEX_CATALOG",
                    "INDEX_SCHEMA",
                    "INDEX_NAME",
                    "TABLE_CATALOG",
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "COLUMN_NAME",
                    "ORDINAL_POSITION INT",
                    "ORDERING_SPECIFICATION",
                    "NULL_ORDERING"
            );
            indexColumnName = "TABLE_NAME";
            break;
        case IN_DOUBT:
            setMetaTableName("IN_DOUBT");
            isView = false;
            cols = createColumns(
                    "TRANSACTION",
                    "STATE"
            );
            break;
        case LOCKS:
            setMetaTableName("LOCKS");
            isView = false;
            cols = createColumns(
                    "TABLE_SCHEMA",
                    "TABLE_NAME",
                    "SESSION_ID INT",
                    "LOCK_TYPE"
            );
            break;
        case QUERY_STATISTICS:
            setMetaTableName("QUERY_STATISTICS");
            isView = false;
            cols = createColumns(
                    "SQL_STATEMENT",
                    "EXECUTION_COUNT INT",
                    "MIN_EXECUTION_TIME DOUBLE",
                    "MAX_EXECUTION_TIME DOUBLE",
                    "CUMULATIVE_EXECUTION_TIME DOUBLE",
                    "AVERAGE_EXECUTION_TIME DOUBLE",
                    "STD_DEV_EXECUTION_TIME DOUBLE",
                    "MIN_ROW_COUNT BIGINT",
                    "MAX_ROW_COUNT BIGINT",
                    "CUMULATIVE_ROW_COUNT LONG",
                    "AVERAGE_ROW_COUNT DOUBLE",
                    "STD_DEV_ROW_COUNT DOUBLE"
            );
            break;
        case RIGHTS:
            setMetaTableName("RIGHTS");
            isView = false;
            cols = createColumns(
                    "GRANTEE",
                    "GRANTEETYPE",
                    "GRANTEDROLE",
                    "RIGHTS",
                    "TABLE_SCHEMA",
                    "TABLE_NAME"
            );
            indexColumnName = "TABLE_NAME";
            break;
        case ROLES:
            setMetaTableName("ROLES");
            isView = false;
            cols = createColumns(
                    "NAME",
                    "REMARKS"
            );
            break;
        case SESSIONS:
            setMetaTableName("SESSIONS");
            isView = false;
            cols = createColumns(
                    "ID INT",
                    "USER_NAME",
                    "SERVER",
                    "CLIENT_ADDR",
                    "CLIENT_INFO",
                    "SESSION_START TIMESTAMP WITH TIME ZONE",
                    "ISOLATION_LEVEL",
                    "STATEMENT",
                    "STATEMENT_START TIMESTAMP WITH TIME ZONE",
                    "CONTAINS_UNCOMMITTED BIT",
                    "STATE",
                    "BLOCKER_ID INT",
                    "SLEEP_SINCE TIMESTAMP WITH TIME ZONE"
            );
            break;
        case SESSION_STATE:
            setMetaTableName("SESSION_STATE");
            isView = false;
            cols = createColumns(
                    "KEY",
                    "SQL"
            );
            break;
        case SETTINGS:
            setMetaTableName("SETTINGS");
            isView = false;
            cols = createColumns("NAME", "VALUE");
            break;
        case SYNONYMS:
            setMetaTableName("SYNONYMS");
            isView = false;
            cols = createColumns(
                    "SYNONYM_CATALOG",
                    "SYNONYM_SCHEMA",
                    "SYNONYM_NAME",
                    "SYNONYM_FOR",
                    "SYNONYM_FOR_SCHEMA",
                    "TYPE_NAME",
                    "STATUS",
                    "REMARKS"
            );
            indexColumnName = "SYNONYM_NAME";
            break;
        case USERS:
            setMetaTableName("USERS");
            isView = false;
            cols = createColumns(
                    "NAME",
                    "ADMIN BOOLEAN",
                    "REMARKS"
            );
            break;
        default:
            throw DbException.throwInternalError("type=" + type);
        }
        setColumns(cols);

        if (indexColumnName == null) {
            indexColumn = -1;
            metaIndex = null;
        } else {
            indexColumn = getColumn(database.sysIdentifier(indexColumnName)).getColumnId();
            IndexColumn[] indexCols = IndexColumn.wrap(new Column[] { cols[indexColumn] });
            metaIndex = new MetaIndex(this, indexCols, false);
        }
        this.isView = isView;
    }

    @Override
    public ArrayList<Row> generateRows(SessionLocal session, SearchRow first, SearchRow last) {
        Value indexFrom = null, indexTo = null;
        if (indexColumn >= 0) {
            if (first != null) {
                indexFrom = first.getValue(indexColumn);
            }
            if (last != null) {
                indexTo = last.getValue(indexColumn);
            }
        }
        ArrayList<Row> rows = Utils.newSmallArrayList();
        String catalog = database.getShortName();
        switch (type) {
        // Standard table
        case INFORMATION_SCHEMA_CATALOG_NAME:
            informationSchemaCatalogName(session, rows, catalog);
            break;
        // Standard views
        case CHECK_CONSTRAINTS:
            checkConstraints(session, indexFrom, indexTo, rows, catalog);
            break;
        case COLLATIONS:
            collations(session, rows, catalog);
            break;
        case COLUMNS:
            columns(session, indexFrom, indexTo, rows, catalog);
            break;
        case COLUMN_PRIVILEGES:
            columnPrivileges(session, indexFrom, indexTo, rows, catalog);
            break;
        case CONSTRAINT_COLUMN_USAGE:
            constraintColumnUsage(session, indexFrom, indexTo, rows, catalog);
            break;
        case DOMAINS:
            domains(session, indexFrom, indexTo, rows, catalog);
            break;
        case DOMAIN_CONSTRAINTS:
            domainConstraints(session, indexFrom, indexTo, rows, catalog);
            break;
        case ELEMENT_TYPES:
            elementTypesFields(session, rows, catalog, ELEMENT_TYPES);
            break;
        case FIELDS:
            elementTypesFields(session, rows, catalog, FIELDS);
            break;
        case KEY_COLUMN_USAGE:
            keyColumnUsage(session, indexFrom, indexTo, rows, catalog);
            break;
        case PARAMETERS:
            parameters(session, rows, catalog);
            break;
        case REFERENTIAL_CONSTRAINTS:
            referentialConstraints(session, indexFrom, indexTo, rows, catalog);
            break;
        case ROUTINES:
            routines(session, rows, catalog);
            break;
        case SCHEMATA:
            schemata(session, rows, catalog);
            break;
        case SEQUENCES:
            sequences(session, indexFrom, indexTo, rows, catalog);
            break;
        case TABLES:
            tables(session, indexFrom, indexTo, rows, catalog);
            break;
        case TABLE_CONSTRAINTS:
            tableConstraints(session, indexFrom, indexTo, rows, catalog);
            break;
        case TABLE_PRIVILEGES:
            tablePrivileges(session, indexFrom, indexTo, rows, catalog);
            break;
        case TRIGGERS:
            triggers(session, indexFrom, indexTo, rows, catalog);
            break;
        case VIEWS:
            views(session, indexFrom, indexTo, rows, catalog);
            break;
        // Extensions
        case CONSTANTS:
            constants(session, indexFrom, indexTo, rows, catalog);
            break;
        case ENUM_VALUES:
            elementTypesFields(session, rows, catalog, ENUM_VALUES);
            break;
        case INDEXES:
            indexes(session, indexFrom, indexTo, rows, catalog, false);
            break;
        case INDEX_COLUMNS:
            indexes(session, indexFrom, indexTo, rows, catalog, true);
            break;
        case IN_DOUBT:
            inDoubt(session, rows);
            break;
        case LOCKS:
            locks(session, rows);
            break;
        case QUERY_STATISTICS:
            queryStatistics(session, rows);
            break;
        case RIGHTS:
            rights(session, indexFrom, indexTo, rows);
            break;
        case ROLES:
            roles(session, rows);
            break;
        case SESSIONS:
            sessions(session, rows);
            break;
        case SESSION_STATE:
            sessionState(session, rows);
            break;
        case SETTINGS:
            settings(session, rows);
            break;
        case SYNONYMS:
            synonyms(session, rows, catalog);
            break;
        case USERS:
            users(session, rows);
            break;
        default:
            DbException.throwInternalError("type=" + type);
        }
        return rows;
    }

    private void informationSchemaCatalogName(SessionLocal session, ArrayList<Row> rows, String catalog) {
        add(session, rows,
                // CATALOG_NAME
                catalog);
    }

    private void checkConstraints(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows,
            String catalog) {
        for (Schema schema : database.getAllSchemas()) {
            for (Constraint constraint : schema.getAllConstraints()) {
                Type constraintType = constraint.getConstraintType();
                if (constraintType == Constraint.Type.CHECK) {
                    ConstraintCheck check = (ConstraintCheck) constraint;
                    Table table = check.getTable();
                    if (hideTable(table, session)) {
                        continue;
                    }
                } else if (constraintType != Constraint.Type.DOMAIN) {
                    continue;
                }
                String constraintName = constraint.getName();
                if (!checkIndex(session, constraintName, indexFrom, indexTo)) {
                    continue;
                }
                checkConstraints(session, rows, catalog, constraint, constraintName);
            }
        }
    }

    private void checkConstraints(SessionLocal session, ArrayList<Row> rows, String catalog, Constraint constraint,
            String constraintName) {
        add(session, rows,
                // CONSTRAINT_CATALOG
                catalog,
                // CONSTRAINT_SCHEMA
                constraint.getSchema().getName(),
                // CONSTRAINT_NAME
                constraintName,
                // CHECK_CLAUSE
                constraint.getExpression().getSQL(DEFAULT_SQL_FLAGS, Expression.WITHOUT_PARENTHESES)
        );
    }

    private void collations(SessionLocal session, ArrayList<Row> rows, String catalog) {
        String mainSchemaName = database.getMainSchema().getName();
        collations(session, rows, catalog, mainSchemaName, "OFF", null);
        for (Locale l : CompareMode.getCollationLocales(false)) {
            collations(session, rows, catalog, mainSchemaName, CompareMode.getName(l), l.toLanguageTag());
        }
    }

    private void collations(SessionLocal session, ArrayList<Row> rows, String catalog, String mainSchemaName,
            String name, String languageTag) {
        if ("und".equals(languageTag)) {
            languageTag = null;
        }
        add(session, rows,
                // COLLATION_CATALOG
                catalog,
                // COLLATION_SCHEMA
                mainSchemaName,
                // COLLATION_NAME
                name,
                // PAD_ATTRIBUTE
                "NO PAD",
                // extensions
                // LANGUAGE_TAG
                languageTag
        );
    }

    private void columns(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows, String catalog) {
        String mainSchemaName = database.getMainSchema().getName();
        String collation = database.getCompareMode().getName();
        if (indexFrom != null && indexFrom.equals(indexTo)) {
            String tableName = indexFrom.getString();
            if (tableName == null) {
                return;
            }
            for (Schema schema : database.getAllSchemas()) {
                Table table = schema.getTableOrViewByName(session, tableName);
                if (table != null) {
                    columns(session, rows, catalog, mainSchemaName, collation, table, table.getName());
                }
            }
            Table table = session.findLocalTempTable(tableName);
            if (table != null) {
                columns(session, rows, catalog, mainSchemaName, collation, table, table.getName());
            }
        } else {
            for (Schema schema : database.getAllSchemas()) {
                for (Table table : schema.getAllTablesAndViews(session)) {
                    String tableName = table.getName();
                    if (checkIndex(session, tableName, indexFrom, indexTo)) {
                        columns(session, rows, catalog, mainSchemaName, collation, table, tableName);
                    }
                }
            }
            for (Table table : session.getLocalTempTables()) {
                String tableName = table.getName();
                if (checkIndex(session, tableName, indexFrom, indexTo)) {
                    columns(session, rows, catalog, mainSchemaName, collation, table, tableName);
                }
            }
        }
    }

    private void columns(SessionLocal session, ArrayList<Row> rows, String catalog, String mainSchemaName,
            String collation, Table table, String tableName) {
        if (hideTable(table, session)) {
            return;
        }
        Column[] cols = table.getColumns();
        for (int i = 0, l = cols.length; i < l;) {
            columns(session, rows, catalog, mainSchemaName, collation, table, tableName, cols[i], ++i);
        }
    }

    private void columns(SessionLocal session, ArrayList<Row> rows, String catalog, String mainSchemaName,
            String collation, Table table, String tableName, Column c, int ordinalPosition) {
        TypeInfo typeInfo = c.getType();
        DataTypeInformation dt = DataTypeInformation.valueOf(typeInfo);
        String characterSetCatalog, characterSetSchema, characterSetName, collationName;
        if (dt.hasCharsetAndCollation) {
            characterSetCatalog = catalog;
            characterSetSchema = mainSchemaName;
            characterSetName = CHARACTER_SET_NAME;
            collationName = collation;
        } else {
            characterSetCatalog = characterSetSchema = characterSetName = collationName = null;
        }
        Domain domain = c.getDomain();
        String domainCatalog = null, domainSchema = null, domainName = null;
        if (domain != null) {
            domainCatalog = catalog;
            domainSchema = domain.getSchema().getName();
            domainName = domain.getName();
        }
        String columnDefault, isGenerated, generationExpression;
        String isIdentity, identityGeneration, identityCycle;
        Value identityStart, identityIncrement, identityMaximum, identityMinimum, identityBase, identityCache;
        Sequence sequence = c.getSequence();
        if (sequence != null) {
            columnDefault = null;
            isGenerated = "NEVER";
            generationExpression = null;
            isIdentity = "YES";
            identityGeneration = c.isGeneratedAlways() ? "ALWAYS" : "BY DEFAULT";
            identityStart = ValueBigint.get(sequence.getStartValue());
            identityIncrement = ValueBigint.get(sequence.getIncrement());
            identityMaximum = ValueBigint.get(sequence.getMaxValue());
            identityMinimum = ValueBigint.get(sequence.getMinValue());
            Sequence.Cycle cycle = sequence.getCycle();
            identityCycle = cycle.isCycle() ? "YES" : "NO";
            identityBase = cycle != Sequence.Cycle.EXHAUSTED ? ValueBigint.get(sequence.getBaseValue()) : null;
            identityCache = ValueBigint.get(sequence.getCacheSize());
        } else {
            if (c.isGenerated()) {
                columnDefault = null;
                isGenerated = "ALWAYS";
                generationExpression = c.getDefaultSQL();
            } else {
                columnDefault = c.getDefaultSQL();
                isGenerated = "NEVER";
                generationExpression = null;
            }
            isIdentity = "NO";
            identityGeneration = identityCycle = null;
            identityStart = identityIncrement = identityMaximum = identityMinimum = identityBase = identityCache
                    = null;
        }
        add(session, rows,
                // TABLE_CATALOG
                catalog,
                // TABLE_SCHEMA
                table.getSchema().getName(),
                // TABLE_NAME
                tableName,
                // COLUMN_NAME
                c.getName(),
                // ORDINAL_POSITION
                ValueInteger.get(ordinalPosition),
                // COLUMN_DEFAULT
                columnDefault,
                // IS_NULLABLE
                c.isNullable() ? "YES" : "NO",
                // DATA_TYPE
                identifier(dt.dataType),
                // CHARACTER_MAXIMUM_LENGTH
                dt.characterPrecision,
                // CHARACTER_OCTET_LENGTH
                dt.characterPrecision,
                // NUMERIC_PRECISION
                dt.numericPrecision,
                // NUMERIC_PRECISION_RADIX
                dt.numericPrecisionRadix,
                // NUMERIC_SCALE
                dt.numericScale,
                // DATETIME_PRECISION
                dt.datetimePrecision,
                // INTERVAL_TYPE
                dt.intervalType,
                // INTERVAL_PRECISION
                dt.intervalPrecision,
                // CHARACTER_SET_CATALOG
                characterSetCatalog,
                // CHARACTER_SET_SCHEMA
                characterSetSchema,
                // CHARACTER_SET_NAME
                characterSetName,
                // COLLATION_CATALOG
                characterSetCatalog,
                // COLLATION_SCHEMA
                characterSetSchema,
                // COLLATION_NAME
                collationName,
                // DOMAIN_CATALOG
                domainCatalog,
                // DOMAIN_SCHEMA
                domainSchema,
                // DOMAIN_NAME
                domainName,
                // MAXIMUM_CARDINALITY
                dt.maximumCardinality,
                // DTD_IDENTIFIER
                Integer.toString(ordinalPosition),
                // IS_IDENTITY
                isIdentity,
                // IDENTITY_GENERATION
                identityGeneration,
                // IDENTITY_START
                identityStart,
                // IDENTITY_INCREMENT
                identityIncrement,
                // IDENTITY_MAXIMUM
                identityMaximum,
                // IDENTITY_MINIMUM
                identityMinimum,
                // IDENTITY_CYCLE
                identityCycle,
                // IS_GENERATED
                isGenerated,
                // GENERATION_EXPRESSION
                generationExpression,
                // DECLARED_DATA_TYPE
                dt.declaredDataType,
                // DECLARED_NUMERIC_PRECISION
                dt.declaredNumericPrecision,
                // DECLARED_NUMERIC_SCALE
                dt.declaredNumericScale,
                // extensions
                // GEOMETRY_TYPE
                dt.geometryType,
                // GEOMETRY_SRID
                dt.geometrySrid,
                // IDENTITY_BASE
                identityBase,
                // IDENTITY_CACHE
                identityCache,
                // COLUMN_ON_UPDATE
                c.getOnUpdateSQL(),
                // IS_VISIBLE
                ValueBoolean.get(c.getVisible()),
                // DEFAULT_ON_NULL
                ValueBoolean.get(c.isDefaultOnNull()),
                // SELECTIVITY
                ValueInteger.get(c.getSelectivity()),
                // REMARKS
                c.getComment()
        );
    }

    private void columnPrivileges(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows,
            String catalog) {
        for (Right r : database.getAllRights()) {
            DbObject object = r.getGrantedObject();
            if (!(object instanceof Table)) {
                continue;
            }
            Table table = (Table) object;
            if (hideTable(table, session)) {
                continue;
            }
            String tableName = table.getName();
            if (!checkIndex(session, tableName, indexFrom, indexTo)) {
                continue;
            }
            DbObject grantee = r.getGrantee();
            int mask = r.getRightMask();
            for (Column column : table.getColumns()) {
                addPrivileges(session, rows, grantee, catalog, table, column.getName(), mask);
            }
        }
    }

    private void constraintColumnUsage(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows,
            String catalog) {
        for (Schema schema : database.getAllSchemas()) {
            for (Constraint constraint : schema.getAllConstraints()) {
                constraintColumnUsage(session, indexFrom, indexTo, rows, catalog, constraint);
            }
        }
    }

    private void constraintColumnUsage(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows,
            String catalog, Constraint constraint) {
        switch (constraint.getConstraintType()) {
        case CHECK:
        case DOMAIN: {
            HashSet<Column> columns = new HashSet<>();
            constraint.getExpression().isEverything(ExpressionVisitor.getColumnsVisitor(columns, null));
            for (Column column : columns) {
                Table table = column.getTable();
                if (checkIndex(session, table.getName(), indexFrom, indexTo) && !hideTable(table, session)) {
                    addConstraintColumnUsage(session, rows, catalog, constraint, column);
                }
            }
            break;
        }
        case REFERENTIAL: {
            Table table = constraint.getRefTable();
            if (checkIndex(session, table.getName(), indexFrom, indexTo) && !hideTable(table, session)) {
                for (Column column : constraint.getReferencedColumns(table)) {
                    addConstraintColumnUsage(session, rows, catalog, constraint, column);
                }
            }
        }
        //$FALL-THROUGH$
        case PRIMARY_KEY:
        case UNIQUE: {
            Table table = constraint.getTable();
            if (checkIndex(session, table.getName(), indexFrom, indexTo) && !hideTable(table, session)) {
                for (Column column : constraint.getReferencedColumns(table)) {
                    addConstraintColumnUsage(session, rows, catalog, constraint, column);
                }
            }
        }
        }
    }

    private void domains(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows, String catalog) {
        String mainSchemaName = database.getMainSchema().getName();
        String collation = database.getCompareMode().getName();
        for (Schema schema : database.getAllSchemas()) {
            for (Domain domain : schema.getAllDomains()) {
                String domainName = domain.getName();
                if (!checkIndex(session, domainName, indexFrom, indexTo)) {
                    continue;
                }
                domains(session, rows, catalog, mainSchemaName, collation, domain, domainName);
            }
        }
    }

    private void domains(SessionLocal session, ArrayList<Row> rows, String catalog, String mainSchemaName,
            String collation, Domain domain, String domainName) {
        Domain parentDomain = domain.getDomain();
        TypeInfo typeInfo = domain.getDataType();
        DataTypeInformation dt = DataTypeInformation.valueOf(typeInfo);
        String characterSetCatalog, characterSetSchema, characterSetName, collationName;
        if (dt.hasCharsetAndCollation) {
            characterSetCatalog = catalog;
            characterSetSchema = mainSchemaName;
            characterSetName = CHARACTER_SET_NAME;
            collationName = collation;
        } else {
            characterSetCatalog = characterSetSchema = characterSetName = collationName = null;
        }
        add(session, rows,
                // DOMAIN_CATALOG
                catalog,
                // DOMAIN_SCHEMA
                domain.getSchema().getName(),
                // DOMAIN_NAME
                domainName,
                // DATA_TYPE
                dt.dataType,
                // CHARACTER_MAXIMUM_LENGTH
                dt.characterPrecision,
                // CHARACTER_OCTET_LENGTH
                dt.characterPrecision,
                // CHARACTER_SET_CATALOG
                characterSetCatalog,
                // CHARACTER_SET_SCHEMA
                characterSetSchema,
                // CHARACTER_SET_NAME
                characterSetName,
                // COLLATION_CATALOG
                characterSetCatalog,
                // COLLATION_SCHEMA
                characterSetSchema,
                // COLLATION_NAME
                collationName,
                // NUMERIC_PRECISION
                dt.numericPrecision,
                // NUMERIC_PRECISION_RADIX
                dt.numericPrecisionRadix,
                // NUMERIC_SCALE
                dt.numericScale,
                // DATETIME_PRECISION
                dt.datetimePrecision,
                // INTERVAL_TYPE
                dt.intervalType,
                // INTERVAL_PRECISION
                dt.intervalPrecision,
                // DOMAIN_DEFAULT
                domain.getDefaultSQL(),
                // MAXIMUM_CARDINALITY
                dt.maximumCardinality,
                // DTD_IDENTIFIER
                "TYPE",
                // DECLARED_DATA_TYPE
                dt.declaredDataType,
                // DECLARED_NUMERIC_PRECISION INT
                dt.declaredNumericPrecision,
                // DECLARED_NUMERIC_SCALE INT
                dt.declaredNumericScale,
                // extensions
                // GEOMETRY_TYPE
                dt.geometryType,
                // GEOMETRY_SRID INT
                dt.geometrySrid,
                // DOMAIN_ON_UPDATE
                domain.getOnUpdateSQL(),
                // PARENT_DOMAIN_CATALOG
                parentDomain != null ? catalog : null,
                // PARENT_DOMAIN_SCHEMA
                parentDomain != null ? parentDomain.getSchema().getName() : null,
                // PARENT_DOMAIN_NAME
                parentDomain != null ? parentDomain.getName() : null,
                // REMARKS
                domain.getComment()
        );
    }

    private void domainConstraints(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows,
            String catalog) {
        for (Schema schema : database.getAllSchemas()) {
            for (Constraint constraint : schema.getAllConstraints()) {
                if (constraint.getConstraintType() != Constraint.Type.DOMAIN) {
                    continue;
                }
                ConstraintDomain domainConstraint = (ConstraintDomain) constraint;
                Domain domain = domainConstraint.getDomain();
                String domainName = domain.getName();
                if (!checkIndex(session, domainName, indexFrom, indexTo)) {
                    continue;
                }
                domainConstraints(session, rows, catalog, domainConstraint, domain, domainName);
            }
        }
    }

    private void domainConstraints(SessionLocal session, ArrayList<Row> rows, String catalog,
            ConstraintDomain constraint, Domain domain, String domainName) {
        add(session, rows,
                // CONSTRAINT_CATALOG
                catalog,
                // CONSTRAINT_SCHEMA
                constraint.getSchema().getName(),
                // CONSTRAINT_NAME
                constraint.getName(),
                // DOMAIN_CATALOG
                catalog,
                // DOMAIN_SCHEMA
                domain.getSchema().getName(),
                // DOMAIN_NAME
                domainName,
                // IS_DEFERRABLE
                "NO",
                // INITIALLY_DEFERRED
                "NO",
                // extensions
                // REMARKS
                constraint.getComment()
        );
    }

    private void elementTypesFields(SessionLocal session, ArrayList<Row> rows, String catalog, int type) {
        String mainSchemaName = database.getMainSchema().getName();
        String collation = database.getCompareMode().getName();
        for (Schema schema : database.getAllSchemas()) {
            String schemaName = schema.getName();
            for (Table table : schema.getAllTablesAndViews(session)) {
                elementTypesFieldsForTable(session, rows, catalog, type, mainSchemaName, collation, schemaName,
                        table);
            }
            for (Domain domain : schema.getAllDomains()) {
                elementTypesFieldsRow(session, rows, catalog, type, mainSchemaName, collation, schemaName,
                        domain.getName(), "DOMAIN", "TYPE", domain.getDataType());
            }
            for (FunctionAlias alias : schema.getAllFunctionAliases()) {
                String name = alias.getName();
                JavaMethod[] methods;
                try {
                    methods = alias.getJavaMethods();
                } catch (DbException e) {
                    methods = new JavaMethod[0];
                }
                for (int i = 0; i < methods.length; i++) {
                    FunctionAlias.JavaMethod method = methods[i];
                    TypeInfo typeInfo = method.getDataType();
                    String specificName = name + '_' + (i + 1);
                    if (typeInfo.getValueType() != Value.NULL) {
                        elementTypesFieldsRow(session, rows, catalog, type, mainSchemaName, collation,
                                schemaName, specificName, "ROUTINE", "RESULT", typeInfo);
                    }
                    Class<?>[] columnList = method.getColumnClasses();
                    for (int o = 1, p = method.hasConnectionParam() ? 1 : 0, n = columnList.length; p < n; o++, p++) {
                        elementTypesFieldsRow(session, rows, catalog, type, mainSchemaName, collation,
                                schemaName, specificName, "ROUTINE", Integer.toString(o),
                                ValueToObjectConverter2.classToType(columnList[p]));
                    }
                }
            }
            for (Constant constant : schema.getAllConstants()) {
                elementTypesFieldsRow(session, rows, catalog, type, mainSchemaName, collation, schemaName,
                        constant.getName(), "CONSTANT", "TYPE", constant.getValue().getType());
            }
        }
        for (Table table : session.getLocalTempTables()) {
            elementTypesFieldsForTable(session, rows, catalog, type, mainSchemaName, collation,
                    table.getSchema().getName(),
                    table);
        }
    }

    private void elementTypesFieldsForTable(SessionLocal session, ArrayList<Row> rows, String catalog, int type,
            String mainSchemaName, String collation, String schemaName, Table table) {
        if (hideTable(table, session)) {
            return;
        }
        String tableName = table.getName();
        Column[] cols = table.getColumns();
        for (int i = 0; i < cols.length; i++) {
            elementTypesFieldsRow(session, rows, catalog, type, mainSchemaName, collation, schemaName,
                    tableName, "TABLE", Integer.toString(i + 1), cols[i].getType());
        }
    }

    private void elementTypesFieldsRow(SessionLocal session, ArrayList<Row> rows, String catalog, int type,
            String mainSchemaName, String collation, String objectSchema, String objectName, String objectType,
            String identifier, TypeInfo typeInfo) {
        switch (typeInfo.getValueType()) {
        case Value.ENUM:
            if (type == ENUM_VALUES) {
                enumValues(session, rows, catalog, objectSchema, objectName, objectType, identifier, typeInfo);
            }
            break;
        case Value.ARRAY: {
            typeInfo = (TypeInfo) typeInfo.getExtTypeInfo();
            String dtdIdentifier = identifier + '_';
            if (type == ELEMENT_TYPES) {
                elementTypes(session, rows, catalog, mainSchemaName, collation, objectSchema, objectName,
                        objectType, identifier, dtdIdentifier, typeInfo);
            }
            elementTypesFieldsRow(session, rows, catalog, type, mainSchemaName, collation, objectSchema,
                    objectName, objectType, dtdIdentifier, typeInfo);
            break;
        }
        case Value.ROW: {
            ExtTypeInfoRow ext = (ExtTypeInfoRow) typeInfo.getExtTypeInfo();
            int ordinalPosition = 0;
            for (Map.Entry<String, TypeInfo> entry : ext.getFields()) {
                typeInfo = entry.getValue();
                String fieldName = entry.getKey();
                String dtdIdentifier = identifier + '_' + ++ordinalPosition;
                if (type == FIELDS) {
                    fields(session, rows, catalog, mainSchemaName, collation, objectSchema, objectName,
                            objectType, identifier, fieldName, ordinalPosition, dtdIdentifier, typeInfo);
                }
                elementTypesFieldsRow(session, rows, catalog, type, mainSchemaName, collation, objectSchema,
                        objectName, objectType, dtdIdentifier, typeInfo);
            }
        }
        }
    }

    private void elementTypes(SessionLocal session, ArrayList<Row> rows, String catalog, String mainSchemaName,
            String collation, String objectSchema, String objectName, String objectType, String collectionIdentifier,
            String dtdIdentifier, TypeInfo typeInfo) {
        DataTypeInformation dt = DataTypeInformation.valueOf(typeInfo);
        String characterSetCatalog, characterSetSchema, characterSetName, collationName;
        if (dt.hasCharsetAndCollation) {
            characterSetCatalog = catalog;
            characterSetSchema = mainSchemaName;
            characterSetName = CHARACTER_SET_NAME;
            collationName = collation;
        } else {
            characterSetCatalog = characterSetSchema = characterSetName = collationName = null;
        }
        add(session, rows,
                // OBJECT_CATALOG
                catalog,
                // OBJECT_SCHEMA
                objectSchema,
                // OBJECT_NAME
                objectName,
                // OBJECT_TYPE
                objectType,
                // COLLECTION_TYPE_IDENTIFIER
                collectionIdentifier,
                // DATA_TYPE
                dt.dataType,
                // CHARACTER_MAXIMUM_LENGTH
                dt.characterPrecision,
                // CHARACTER_OCTET_LENGTH
                dt.characterPrecision,
                // CHARACTER_SET_CATALOG
                characterSetCatalog,
                // CHARACTER_SET_SCHEMA
                characterSetSchema,
                // CHARACTER_SET_NAME
                characterSetName,
                // COLLATION_CATALOG
                characterSetCatalog,
                // COLLATION_SCHEMA
                characterSetSchema,
                // COLLATION_NAME
                collationName,
                // NUMERIC_PRECISION
                dt.numericPrecision,
                // NUMERIC_PRECISION_RADIX
                dt.numericPrecisionRadix,
                // NUMERIC_SCALE
                dt.numericScale,
                // DATETIME_PRECISION
                dt.datetimePrecision,
                // INTERVAL_TYPE
                dt.intervalType,
                // INTERVAL_PRECISION
                dt.intervalPrecision,
                // MAXIMUM_CARDINALITY
                dt.maximumCardinality,
                // DTD_IDENTIFIER
                dtdIdentifier,
                // DECLARED_DATA_TYPE
                dt.declaredDataType,
                // DECLARED_NUMERIC_PRECISION INT
                dt.declaredNumericPrecision,
                // DECLARED_NUMERIC_SCALE INT
                dt.declaredNumericScale,
                // extensions
                // GEOMETRY_TYPE
                dt.geometryType,
                // GEOMETRY_SRID INT
                dt.geometrySrid
        );
    }

    private void fields(SessionLocal session, ArrayList<Row> rows, String catalog, String mainSchemaName,
            String collation, String objectSchema, String objectName, String objectType, String rowIdentifier,
            String fieldName, int ordinalPosition, String dtdIdentifier, TypeInfo typeInfo) {
        DataTypeInformation dt = DataTypeInformation.valueOf(typeInfo);
        String characterSetCatalog, characterSetSchema, characterSetName, collationName;
        if (dt.hasCharsetAndCollation) {
            characterSetCatalog = catalog;
            characterSetSchema = mainSchemaName;
            characterSetName = CHARACTER_SET_NAME;
            collationName = collation;
        } else {
            characterSetCatalog = characterSetSchema = characterSetName = collationName = null;
        }
        add(session, rows,
                // OBJECT_CATALOG
                catalog,
                // OBJECT_SCHEMA
                objectSchema,
                // OBJECT_NAME
                objectName,
                // OBJECT_TYPE
                objectType,
                // ROW_IDENTIFIER
                rowIdentifier,
                // FIELD_NAME
                fieldName,
                // ORDINAL_POSITION
                ValueInteger.get(ordinalPosition),
                // DATA_TYPE
                dt.dataType,
                // CHARACTER_MAXIMUM_LENGTH
                dt.characterPrecision,
                // CHARACTER_OCTET_LENGTH
                dt.characterPrecision,
                // CHARACTER_SET_CATALOG
                characterSetCatalog,
                // CHARACTER_SET_SCHEMA
                characterSetSchema,
                // CHARACTER_SET_NAME
                characterSetName,
                // COLLATION_CATALOG
                characterSetCatalog,
                // COLLATION_SCHEMA
                characterSetSchema,
                // COLLATION_NAME
                collationName,
                // NUMERIC_PRECISION
                dt.numericPrecision,
                // NUMERIC_PRECISION_RADIX
                dt.numericPrecisionRadix,
                // NUMERIC_SCALE
                dt.numericScale,
                // DATETIME_PRECISION
                dt.datetimePrecision,
                // INTERVAL_TYPE
                dt.intervalType,
                // INTERVAL_PRECISION
                dt.intervalPrecision,
                // MAXIMUM_CARDINALITY
                dt.maximumCardinality,
                // DTD_IDENTIFIER
                dtdIdentifier,
                // DECLARED_DATA_TYPE
                dt.declaredDataType,
                // DECLARED_NUMERIC_PRECISION INT
                dt.declaredNumericPrecision,
                // DECLARED_NUMERIC_SCALE INT
                dt.declaredNumericScale,
                // extensions
                // GEOMETRY_TYPE
                dt.geometryType,
                // GEOMETRY_SRID INT
                dt.geometrySrid
        );
    }

    private void keyColumnUsage(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows,
            String catalog) {
        for (Schema schema : database.getAllSchemas()) {
            for (Constraint constraint : schema.getAllConstraints()) {
                Constraint.Type constraintType = constraint.getConstraintType();
                IndexColumn[] indexColumns = null;
                if (constraintType == Constraint.Type.UNIQUE || constraintType == Constraint.Type.PRIMARY_KEY) {
                    indexColumns = ((ConstraintUnique) constraint).getColumns();
                } else if (constraintType == Constraint.Type.REFERENTIAL) {
                    indexColumns = ((ConstraintReferential) constraint).getColumns();
                }
                if (indexColumns == null) {
                    continue;
                }
                Table table = constraint.getTable();
                if (hideTable(table, session)) {
                    continue;
                }
                String tableName = table.getName();
                if (!checkIndex(session, tableName, indexFrom, indexTo)) {
                    continue;
                }
                keyColumnUsage(session, rows, catalog, constraint, constraintType, indexColumns, table, tableName);
            }
        }
    }

    private void keyColumnUsage(SessionLocal session, ArrayList<Row> rows, String catalog, Constraint constraint,
            Constraint.Type constraintType, IndexColumn[] indexColumns, Table table, String tableName) {
        ConstraintUnique referenced;
        if (constraintType == Constraint.Type.REFERENTIAL) {
            referenced = ((ConstraintReferential) constraint).getReferencedConstraint();
        } else {
            referenced = null;
        }
        for (int i = 0; i < indexColumns.length; i++) {
            IndexColumn indexColumn = indexColumns[i];
            ValueInteger ordinalPosition = ValueInteger.get(i + 1);
            ValueInteger positionInUniqueConstraint = null;
            if (referenced != null) {
                Column c = ((ConstraintReferential) constraint).getRefColumns()[i].column;
                IndexColumn[] refColumns = referenced.getColumns();
                for (int j = 0; j < refColumns.length; j++) {
                    if (refColumns[j].column.equals(c)) {
                        positionInUniqueConstraint = ValueInteger.get(j + 1);
                        break;
                    }
                }
            }
            add(session, rows,
                    // CONSTRAINT_CATALOG
                    catalog,
                    // CONSTRAINT_SCHEMA
                    constraint.getSchema().getName(),
                    // CONSTRAINT_NAME
                    constraint.getName(),
                    // TABLE_CATALOG
                    catalog,
                    // TABLE_SCHEMA
                    table.getSchema().getName(),
                    // TABLE_NAME
                    tableName,
                    // COLUMN_NAME
                    indexColumn.columnName,
                    // ORDINAL_POSITION
                    ordinalPosition,
                    // POSITION_IN_UNIQUE_CONSTRAINT
                    positionInUniqueConstraint
            );
        }
    }

    private void parameters(SessionLocal session, ArrayList<Row> rows, String catalog) {
        String mainSchemaName = database.getMainSchema().getName();
        String collation = database.getCompareMode().getName();
        for (Schema schema : database.getAllSchemas()) {
            for (FunctionAlias alias : schema.getAllFunctionAliases()) {
                JavaMethod[] methods;
                try {
                    methods = alias.getJavaMethods();
                } catch (DbException e) {
                    methods = new JavaMethod[0];
                }
                for (int i = 0; i < methods.length; i++) {
                    FunctionAlias.JavaMethod method = methods[i];
                    Class<?>[] columnList = method.getColumnClasses();
                    for (int o = 1, p = method.hasConnectionParam() ? 1 : 0, n = columnList.length; p < n; o++, p++) {
                        parameters(session, rows, catalog, mainSchemaName, collation, schema.getName(),
                                alias.getName() + '_' + (i + 1), ValueToObjectConverter2.classToType(columnList[p]), //
                                o);
                    }
                }
            }
        }
    }

    private void parameters(SessionLocal session, ArrayList<Row> rows, String catalog, String mainSchemaName,
            String collation, String schema, String specificName, TypeInfo typeInfo, int pos) {
        DataTypeInformation dt = DataTypeInformation.valueOf(typeInfo);
        String characterSetCatalog, characterSetSchema, characterSetName, collationName;
        if (dt.hasCharsetAndCollation) {
            characterSetCatalog = catalog;
            characterSetSchema = mainSchemaName;
            characterSetName = CHARACTER_SET_NAME;
            collationName = collation;
        } else {
            characterSetCatalog = characterSetSchema = characterSetName = collationName = null;
        }
        add(session, rows,
                // SPECIFIC_CATALOG
                catalog,
                // SPECIFIC_SCHEMA
                schema,
                // SPECIFIC_NAME
                specificName,
                // ORDINAL_POSITION
                ValueInteger.get(pos),
                // PARAMETER_MODE
                "IN",
                // IS_RESULT
                "NO",
                // AS_LOCATOR
                DataType.isLargeObject(typeInfo.getValueType()) ? "YES" : "NO",
                // PARAMETER_NAME
                "P" + pos,
                // DATA_TYPE
                identifier(dt.dataType),
                // CHARACTER_MAXIMUM_LENGTH
                dt.characterPrecision,
                // CHARACTER_OCTET_LENGTH
                dt.characterPrecision,
                // CHARACTER_SET_CATALOG
                characterSetCatalog,
                // CHARACTER_SET_SCHEMA
                characterSetSchema,
                // CHARACTER_SET_NAME
                characterSetName,
                // COLLATION_CATALOG
                characterSetCatalog,
                // COLLATION_SCHEMA
                characterSetSchema,
                // COLLATION_NAME
                collationName,
                // NUMERIC_PRECISION
                dt.numericPrecision,
                // NUMERIC_PRECISION_RADIX
                dt.numericPrecisionRadix,
                // NUMERIC_SCALE
                dt.numericScale,
                // DATETIME_PRECISION
                dt.datetimePrecision,
                // INTERVAL_TYPE
                dt.intervalType,
                // INTERVAL_PRECISION
                dt.intervalPrecision,
                // MAXIMUM_CARDINALITY
                dt.maximumCardinality,
                // DTD_IDENTIFIER
                Integer.toString(pos),
                // DECLARED_DATA_TYPE
                dt.declaredDataType,
                // DECLARED_NUMERIC_PRECISION INT
                dt.declaredNumericPrecision,
                // DECLARED_NUMERIC_SCALE INT
                dt.declaredNumericScale,
                // PARAMETER_DEFAULT
                null,
                // extensions
                // GEOMETRY_TYPE
                dt.geometryType,
                // GEOMETRY_SRID INT
                dt.geometrySrid
        );
    }

    private void referentialConstraints(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows,
            String catalog) {
        for (Schema schema : database.getAllSchemas()) {
            for (Constraint constraint : schema.getAllConstraints()) {
                if (constraint.getConstraintType() != Constraint.Type.REFERENTIAL) {
                    continue;
                }
                if (hideTable(constraint.getTable(), session)) {
                    continue;
                }
                String constraintName = constraint.getName();
                if (!checkIndex(session, constraintName, indexFrom, indexTo)) {
                    continue;
                }
                referentialConstraints(session, rows, catalog, (ConstraintReferential) constraint, constraintName);
            }
        }
    }

    private void referentialConstraints(SessionLocal session, ArrayList<Row> rows, String catalog,
            ConstraintReferential constraint, String constraintName) {
        ConstraintUnique unique = constraint.getReferencedConstraint();
        add(session, rows,
                // CONSTRAINT_CATALOG
                catalog,
                // CONSTRAINT_SCHEMA
                constraint.getSchema().getName(),
                // CONSTRAINT_NAME
                constraintName,
                // UNIQUE_CONSTRAINT_CATALOG
                catalog,
                // UNIQUE_CONSTRAINT_SCHEMA
                unique.getSchema().getName(),
                // UNIQUE_CONSTRAINT_NAME
                unique.getName(),
                // MATCH_OPTION
                "NONE",
                // UPDATE_RULE
                constraint.getUpdateAction().getSqlName(),
                // DELETE_RULE
                constraint.getDeleteAction().getSqlName()
        );
    }

    private void routines(SessionLocal session, ArrayList<Row> rows, String catalog) {
        boolean admin = session.getUser().isAdmin();
        String mainSchemaName = database.getMainSchema().getName();
        String collation = database.getCompareMode().getName();
        for (Schema schema : database.getAllSchemas()) {
            String schemaName = schema.getName();
            for (FunctionAlias alias : schema.getAllFunctionAliases()) {
                String name = alias.getName();
                JavaMethod[] methods;
                try {
                    methods = alias.getJavaMethods();
                } catch (DbException e) {
                    methods = new JavaMethod[0];
                }
                for (int i = 0; i < methods.length; i++) {
                    FunctionAlias.JavaMethod method = methods[i];
                    TypeInfo typeInfo = method.getDataType();
                    String routineType;
                    if (typeInfo.getValueType() == Value.NULL) {
                        routineType = "PROCEDURE";
                        typeInfo = null;
                    } else {
                        routineType = "FUNCTION";
                    }
                    routines(session, rows, catalog, mainSchemaName, collation, schemaName, name,
                            name + '_' + (i + 1), routineType, admin ? alias.getSource() : null,
                            alias.getJavaClassName() + '.' + alias.getJavaMethodName(), typeInfo,
                            alias.isDeterministic(), alias.getComment());
                }
            }
            for (UserAggregate agg : schema.getAllAggregates()) {
                String name = agg.getName();
                routines(session, rows, catalog, mainSchemaName, collation, schemaName, name, name,
                        "AGGREGATE", null, agg.getJavaClassName(), TypeInfo.TYPE_NULL, false, agg.getComment());
            }
        }
    }

    private void routines(SessionLocal session, ArrayList<Row> rows, String catalog, String mainSchemaName, //
            String collation, String schema, String name, String specificName, String routineType, String definition,
            String externalName, TypeInfo typeInfo, boolean deterministic, String remarks) {
        DataTypeInformation dt = typeInfo != null ? DataTypeInformation.valueOf(typeInfo) : DataTypeInformation.NULL;
        String characterSetCatalog, characterSetSchema, characterSetName, collationName;
        if (dt.hasCharsetAndCollation) {
            characterSetCatalog = catalog;
            characterSetSchema = mainSchemaName;
            characterSetName = CHARACTER_SET_NAME;
            collationName = collation;
        } else {
            characterSetCatalog = characterSetSchema = characterSetName = collationName = null;
        }
        add(session, rows,
                // SPECIFIC_CATALOG
                catalog,
                // SPECIFIC_SCHEMA
                schema,
                // SPECIFIC_NAME
                specificName,
                // ROUTINE_CATALOG
                catalog,
                // ROUTINE_SCHEMA
                schema,
                // ROUTINE_NAME
                name,
                // ROUTINE_TYPE
                routineType,
                // DATA_TYPE
                identifier(dt.dataType),
                // CHARACTER_MAXIMUM_LENGTH
                dt.characterPrecision,
                // CHARACTER_OCTET_LENGTH
                dt.characterPrecision,
                // CHARACTER_SET_CATALOG
                characterSetCatalog,
                // CHARACTER_SET_SCHEMA
                characterSetSchema,
                // CHARACTER_SET_NAME
                characterSetName,
                // COLLATION_CATALOG
                characterSetCatalog,
                // COLLATION_SCHEMA
                characterSetSchema,
                // COLLATION_NAME
                collationName,
                // NUMERIC_PRECISION
                dt.numericPrecision,
                // NUMERIC_PRECISION_RADIX
                dt.numericPrecisionRadix,
                // NUMERIC_SCALE
                dt.numericScale,
                // DATETIME_PRECISION
                dt.datetimePrecision,
                // INTERVAL_TYPE
                dt.intervalType,
                // INTERVAL_PRECISION
                dt.intervalPrecision,
                // MAXIMUM_CARDINALITY
                dt.maximumCardinality,
                // DTD_IDENTIFIER
                "RESULT",
                // ROUTINE_BODY
                "EXTERNAL",
                // ROUTINE_DEFINITION
                definition,
                // EXTERNAL_NAME
                externalName,
                // EXTERNAL_LANGUAGE
                "JAVA",
                // PARAMETER_STYLE
                "GENERAL",
                // IS_DETERMINISTIC
                deterministic ? "YES" : "NO",
                // DECLARED_DATA_TYPE
                dt.declaredDataType,
                // DECLARED_NUMERIC_PRECISION INT
                dt.declaredNumericPrecision,
                // DECLARED_NUMERIC_SCALE INT
                dt.declaredNumericScale,
                // extensions
                // GEOMETRY_TYPE
                dt.geometryType,
                // GEOMETRY_SRID INT
                dt.geometrySrid,
                // REMARKS
                remarks
        );
    }

    private void schemata(SessionLocal session, ArrayList<Row> rows, String catalog) {
        String mainSchemaName = database.getMainSchema().getName();
        String collation = database.getCompareMode().getName();
        for (Schema schema : database.getAllSchemas()) {
            add(session, rows,
                    // CATALOG_NAME
                    catalog,
                    // SCHEMA_NAME
                    schema.getName(),
                    // SCHEMA_OWNER
                    identifier(schema.getOwner().getName()),
                    // DEFAULT_CHARACTER_SET_CATALOG
                    catalog,
                    // DEFAULT_CHARACTER_SET_SCHEMA
                    mainSchemaName,
                    // DEFAULT_CHARACTER_SET_NAME
                    CHARACTER_SET_NAME,
                    // SQL_PATH
                    null,
                    // extensions
                    // DEFAULT_COLLATION_NAME
                    collation,
                    // REMARKS
                    schema.getComment()
            );
        }
    }

    private void sequences(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows, String catalog) {
        for (Schema schema : database.getAllSchemas()) {
            for (Sequence sequence : schema.getAllSequences()) {
                if (sequence.getBelongsToTable()) {
                    continue;
                }
                String sequenceName = sequence.getName();
                if (!checkIndex(session, sequenceName, indexFrom, indexTo)) {
                    continue;
                }
                sequences(session, rows, catalog, sequence, sequenceName);
            }
        }
    }

    private void sequences(SessionLocal session, ArrayList<Row> rows, String catalog, Sequence sequence,
            String sequenceName) {
        DataTypeInformation dt = DataTypeInformation.valueOf(sequence.getDataType());
        Sequence.Cycle cycle = sequence.getCycle();
        add(session, rows,
                // SEQUENCE_CATALOG
                catalog,
                // SEQUENCE_SCHEMA
                sequence.getSchema().getName(),
                // SEQUENCE_NAME
                sequenceName,
                // DATA_TYPE
                dt.dataType,
                // NUMERIC_PRECISION
                ValueInteger.get(sequence.getEffectivePrecision()),
                // NUMERIC_PRECISION_RADIX
                dt.numericPrecisionRadix,
                // NUMERIC_SCALE
                dt.numericScale,
                // START_VALUE
                ValueBigint.get(sequence.getStartValue()),
                // MINIMUM_VALUE
                ValueBigint.get(sequence.getMinValue()),
                // MAXIMUM_VALUE
                ValueBigint.get(sequence.getMaxValue()),
                // INCREMENT
                ValueBigint.get(sequence.getIncrement()),
                // CYCLE_OPTION
                cycle.isCycle() ? "YES" : "NO",
                // DECLARED_DATA_TYPE
                dt.declaredDataType,
                // DECLARED_NUMERIC_PRECISION
                dt.declaredNumericPrecision,
                // DECLARED_NUMERIC_SCALE
                dt.declaredNumericScale,
                // extensions
                // BASE_VALUE
                cycle != Sequence.Cycle.EXHAUSTED ? ValueBigint.get(sequence.getBaseValue()) : null,
                // CACHE
                ValueBigint.get(sequence.getCacheSize()),
                // REMARKS
                sequence.getComment()
            );
    }

    private void tables(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows, String catalog) {
        boolean admin = session.getUser().isAdmin();
        for (Schema schema : database.getAllSchemas()) {
            for (Table table : schema.getAllTablesAndViews(session)) {
                String tableName = table.getName();
                if (checkIndex(session, tableName, indexFrom, indexTo)) {
                    tables(session, rows, catalog, admin, table, tableName);
                }
            }
        }
        for (Table table : session.getLocalTempTables()) {
            String tableName = table.getName();
            if (checkIndex(session, tableName, indexFrom, indexTo)) {
                tables(session, rows, catalog, admin, table, tableName);
            }
        }
    }

    private void tables(SessionLocal session, ArrayList<Row> rows, String catalog, boolean admin, Table table,
            String tableName) {
        if (hideTable(table, session)) {
            return;
        }
        String commitAction, storageType;
        if (table.isTemporary()) {
            commitAction = table.getOnCommitTruncate() ? "DELETE" : table.getOnCommitDrop() ? "DROP" : "PRESERVE";
            storageType = table.isGlobalTemporary() ? "GLOBAL TEMPORARY" : "LOCAL TEMPORARY";
        } else {
            commitAction = null;
            switch (table.getTableType()) {
            case TABLE_LINK:
                storageType = "TABLE LINK";
                break;
            case EXTERNAL_TABLE_ENGINE:
                storageType = "EXTERNAL";
                break;
            default:
                storageType = table.isPersistIndexes() ? "CACHED" : "MEMORY";
                break;
            }
        }
        long lastModification = table.getMaxDataModificationId();
        add(session, rows,
                // TABLE_CATALOG
                catalog,
                // TABLE_SCHEMA
                table.getSchema().getName(),
                // TABLE_NAME
                tableName,
                // TABLE_TYPE
                table.getSQLTableType(),
                // IS_INSERTABLE_INTO"
                table.isInsertable() ? "YES" : "NO",
                // COMMIT_ACTION
                commitAction,
                // extensions
                // STORAGE_TYPE
                storageType,
                // REMARKS
                table.getComment(),
                // LAST_MODIFICATION
                lastModification != Long.MAX_VALUE ? ValueBigint.get(lastModification) : null,
                // TABLE_CLASS
                table.getClass().getName(),
                // ROW_COUNT_ESTIMATE
                ValueBigint.get(table.getRowCountApproximation(session))
        );
    }

    private void tableConstraints(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows,
            String catalog) {
        for (Schema schema : database.getAllSchemas()) {
            for (Constraint constraint : schema.getAllConstraints()) {
                Constraint.Type constraintType = constraint.getConstraintType();
                if (constraintType == Constraint.Type.DOMAIN) {
                    continue;
                }
                Table table = constraint.getTable();
                if (hideTable(table, session)) {
                    continue;
                }
                String tableName = table.getName();
                if (!checkIndex(session, tableName, indexFrom, indexTo)) {
                    continue;
                }
                tableConstraints(session, rows, catalog, constraint, constraintType, table, tableName);
            }
        }
    }

    private void tableConstraints(SessionLocal session, ArrayList<Row> rows, String catalog, Constraint constraint,
            Constraint.Type constraintType, Table table, String tableName) {
        Index index = constraint.getIndex();
        boolean enforced;
        if (constraintType != Constraint.Type.REFERENTIAL) {
            enforced = true;
        } else {
            enforced = database.getReferentialIntegrity() && table.getCheckForeignKeyConstraints()
                    && ((ConstraintReferential) constraint).getRefTable().getCheckForeignKeyConstraints();
        }
        add(session, rows,
                // CONSTRAINT_CATALOG
                catalog,
                // CONSTRAINT_SCHEMA
                constraint.getSchema().getName(),
                // CONSTRAINT_NAME
                constraint.getName(),
                // CONSTRAINT_TYPE
                constraintType.getSqlName(),
                // TABLE_CATALOG
                catalog,
                // TABLE_SCHEMA
                table.getSchema().getName(),
                // TABLE_NAME
                tableName,
                // IS_DEFERRABLE
                "NO",
                // INITIALLY_DEFERRED
                "NO",
                // ENFORCED
                enforced ? "YES" : "NO",
                // extensions
                // INDEX_CATALOG
                index != null ? catalog : null,
                // INDEX_SCHEMA
                index != null ? index.getSchema().getName() : null,
                // INDEX_NAME
                index != null ? index.getName() : null,
                // REMARKS
                constraint.getComment()
        );
    }

    private void tablePrivileges(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows, //
            String catalog) {
        for (Right r : database.getAllRights()) {
            DbObject object = r.getGrantedObject();
            if (!(object instanceof Table)) {
                continue;
            }
            Table table = (Table) object;
            if (hideTable(table, session)) {
                continue;
            }
            String tableName = table.getName();
            if (!checkIndex(session, tableName, indexFrom, indexTo)) {
                continue;
            }
            addPrivileges(session, rows, r.getGrantee(), catalog, table, null, r.getRightMask());
        }
    }

    private void triggers(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows, String catalog) {
        for (Schema schema : database.getAllSchemas()) {
            for (TriggerObject trigger : schema.getAllTriggers()) {
                Table table = trigger.getTable();
                String tableName = table.getName();
                if (!checkIndex(session, tableName, indexFrom, indexTo)) {
                    continue;
                }
                triggers(session, rows, catalog, trigger, table, tableName);
            }
        }
    }

    private void triggers(SessionLocal session, ArrayList<Row> rows, String catalog, TriggerObject trigger, //
            Table table, String tableName) {
        add(session, rows,
                // TRIGGER_CATALOG
                catalog,
                // TRIGGER_SCHEMA
                trigger.getSchema().getName(),
                // TRIGGER_NAME
                trigger.getName(),
                // extensions
                // TRIGGER_TYPE
                trigger.getTypeNameList(new StringBuilder()).toString(),
                // TABLE_CATALOG
                catalog,
                // TABLE_SCHEMA
                table.getSchema().getName(),
                // TABLE_NAME
                tableName,
                // BEFORE
                ValueBoolean.get(trigger.isBefore()),
                // JAVA_CLASS
                trigger.getTriggerClassName(),
                // QUEUE_SIZE
                ValueInteger.get(trigger.getQueueSize()),
                // NO_WAIT
                ValueBoolean.get(trigger.isNoWait()),
                // REMARKS
                trigger.getComment()
        );
    }

    private void views(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows, String catalog) {
        for (Schema schema : database.getAllSchemas()) {
            for (Table table : schema.getAllTablesAndViews(session)) {
                if (table.isView()) {
                    String tableName = table.getName();
                    if (checkIndex(session, tableName, indexFrom, indexTo)) {
                        views(session, rows, catalog, table, tableName);
                    }
                }
            }
        }
        for (Table table : session.getLocalTempTables()) {
            if (table.isView()) {
                String tableName = table.getName();
                if (checkIndex(session, tableName, indexFrom, indexTo)) {
                    views(session, rows, catalog, table, tableName);
                }
            }
        }
    }

    private void views(SessionLocal session, ArrayList<Row> rows, String catalog, Table table, String tableName) {
        String viewDefinition, status = "VALID";
        if (table instanceof TableView) {
            TableView view = (TableView) table;
            viewDefinition = view.getQuery();
            if (view.isInvalid()) {
                status = "INVALID";
            }
        } else {
            viewDefinition = null;
        }
        int mask = 0;
        ArrayList<TriggerObject> triggers = table.getTriggers();
        if (triggers != null) {
            for (TriggerObject trigger : triggers) {
                if (trigger.isInsteadOf()) {
                    mask |= trigger.getTypeMask();
                }
            }
        }
        add(session, rows,
                // TABLE_CATALOG
                catalog,
                // TABLE_SCHEMA
                table.getSchema().getName(),
                // TABLE_NAME
                tableName,
                // VIEW_DEFINITION
                viewDefinition,
                // CHECK_OPTION
                "NONE",
                // IS_UPDATABLE
                "NO",
                // INSERTABLE_INTO
                "NO",
                // IS_TRIGGER_UPDATABLE
                (mask & Trigger.UPDATE) != 0 ? "YES" : "NO",
                // IS_TRIGGER_DELETABLE
                (mask & Trigger.DELETE) != 0 ? "YES" : "NO",
                // IS_TRIGGER_INSERTABLE_INTO
                (mask & Trigger.INSERT) != 0 ? "YES" : "NO",
                // extensions
                // STATUS
                status,
                // REMARKS
                table.getComment()
        );
    }

    private void constants(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows, String catalog) {
        String mainSchemaName = database.getMainSchema().getName();
        String collation = database.getCompareMode().getName();
        for (Schema schema : database.getAllSchemas()) {
            for (Constant constant : schema.getAllConstants()) {
                String constantName = constant.getName();
                if (!checkIndex(session, constantName, indexFrom, indexTo)) {
                    continue;
                }
                constants(session, rows, catalog, mainSchemaName, collation, constant, constantName);
            }
        }
    }

    private void constants(SessionLocal session, ArrayList<Row> rows, String catalog, String mainSchemaName,
            String collation, Constant constant, String constantName) {
        ValueExpression expr = constant.getValue();
        TypeInfo typeInfo = expr.getType();
        DataTypeInformation dt = DataTypeInformation.valueOf(typeInfo);
        String characterSetCatalog, characterSetSchema, characterSetName, collationName;
        if (dt.hasCharsetAndCollation) {
            characterSetCatalog = catalog;
            characterSetSchema = mainSchemaName;
            characterSetName = CHARACTER_SET_NAME;
            collationName = collation;
        } else {
            characterSetCatalog = characterSetSchema = characterSetName = collationName = null;
        }
        add(session, rows,
                // CONSTANT_CATALOG
                catalog,
                // CONSTANT_SCHEMA
                constant.getSchema().getName(),
                // CONSTANT_NAME
                constantName,
                // VALUE_DEFINITION
                expr.getSQL(DEFAULT_SQL_FLAGS),
                // DATA_TYPE
                dt.dataType,
                // CHARACTER_MAXIMUM_LENGTH
                dt.characterPrecision,
                // CHARACTER_OCTET_LENGTH
                dt.characterPrecision,
                // CHARACTER_SET_CATALOG
                characterSetCatalog,
                // CHARACTER_SET_SCHEMA
                characterSetSchema,
                // CHARACTER_SET_NAME
                characterSetName,
                // COLLATION_CATALOG
                characterSetCatalog,
                // COLLATION_SCHEMA
                characterSetSchema,
                // COLLATION_NAME
                collationName,
                // NUMERIC_PRECISION
                dt.numericPrecision,
                // NUMERIC_PRECISION_RADIX
                dt.numericPrecisionRadix,
                // NUMERIC_SCALE
                dt.numericScale,
                // DATETIME_PRECISION
                dt.datetimePrecision,
                // INTERVAL_TYPE
                dt.intervalType,
                // INTERVAL_PRECISION
                dt.intervalPrecision,
                // MAXIMUM_CARDINALITY
                dt.maximumCardinality,
                // DTD_IDENTIFIER
                "TYPE",
                // DECLARED_DATA_TYPE
                dt.declaredDataType,
                // DECLARED_NUMERIC_PRECISION INT
                dt.declaredNumericPrecision,
                // DECLARED_NUMERIC_SCALE INT
                dt.declaredNumericScale,
                // GEOMETRY_TYPE
                dt.geometryType,
                // GEOMETRY_SRID INT
                dt.geometrySrid,
                // REMARKS
                constant.getComment()
            );
    }

    private void enumValues(SessionLocal session, ArrayList<Row> rows, String catalog, String objectSchema,
            String objectName, String objectType, String enumIdentifier, TypeInfo typeInfo) {
        ExtTypeInfoEnum ext = (ExtTypeInfoEnum) typeInfo.getExtTypeInfo();
        if (ext == null) {
            return;
        }
        for (int i = 0, ordinal = session.zeroBasedEnums() ? 0 : 1, l = ext.getCount(); i < l; i++, ordinal++) {
            add(session, rows,
                    // OBJECT_CATALOG
                    catalog,
                    // OBJECT_SCHEMA
                    objectSchema,
                    // OBJECT_NAME
                    objectName,
                    // OBJECT_TYPE
                    objectType,
                    // ENUM_IDENTIFIER
                    enumIdentifier,
                    // VALUE_NAME
                    ext.getEnumerator(i),
                    // VALUE_ORDINAL
                    ValueInteger.get(ordinal)
            );
        }
    }

    private void indexes(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows, String catalog,
            boolean columns) {
        if (indexFrom != null && indexFrom.equals(indexTo)) {
            String tableName = indexFrom.getString();
            if (tableName == null) {
                return;
            }
            for (Schema schema : database.getAllSchemas()) {
                Table table = schema.getTableOrViewByName(session, tableName);
                if (table != null) {
                    indexes(session, rows, catalog, columns, table, table.getName());
                }
            }
            Table table = session.findLocalTempTable(tableName);
            if (table != null) {
                indexes(session, rows, catalog, columns, table, table.getName());
            }
        } else {
            for (Schema schema : database.getAllSchemas()) {
                for (Table table : schema.getAllTablesAndViews(session)) {
                    String tableName = table.getName();
                    if (checkIndex(session, tableName, indexFrom, indexTo)) {
                        indexes(session, rows, catalog, columns, table, tableName);
                    }
                }
            }
            for (Table table : session.getLocalTempTables()) {
                String tableName = table.getName();
                if (checkIndex(session, tableName, indexFrom, indexTo)) {
                    indexes(session, rows, catalog, columns, table, tableName);
                }
            }
        }
    }

    private void indexes(SessionLocal session, ArrayList<Row> rows, String catalog, boolean columns, Table table,
            String tableName) {
        if (hideTable(table, session)) {
            return;
        }
        ArrayList<Index> indexes = table.getIndexes();
        if (indexes == null) {
            return;
        }
        for (Index index : indexes) {
            if (index.getCreateSQL() == null) {
                continue;
            }
            if (columns) {
                indexColumns(session, rows, catalog, table, tableName, index);
            } else {
                indexes(session, rows, catalog, table, tableName, index);
            }
        }
    }

    private void indexes(SessionLocal session, ArrayList<Row> rows, String catalog, Table table, String tableName,
            Index index) {
        add(session, rows,
                // INDEX_CATALOG
                catalog,
                // INDEX_SCHEMA
                index.getSchema().getName(),
                // INDEX_NAME
                index.getName(),
                // TABLE_CATALOG
                catalog,
                // TABLE_SCHEMA
                table.getSchema().getName(),
                // TABLE_NAME
                tableName,
                // INDEX_TYPE_NAME
                index.getIndexType().getSQL(),
                // IS_GENERATED
                ValueBoolean.get(index.getIndexType().getBelongsToConstraint()),
                // REMARKS
                index.getComment(),
                // INDEX_CLASS
                index.getClass().getName()
            );
    }

    private void indexColumns(SessionLocal session, ArrayList<Row> rows, String catalog, Table table,
            String tableName, Index index) {
        IndexColumn[] cols = index.getIndexColumns();
        for (int i = 0, l = cols.length; i < l;) {
            IndexColumn idxCol = cols[i];
            int sortType = idxCol.sortType;
            add(session, rows,
                    // INDEX_CATALOG
                    catalog,
                    // INDEX_SCHEMA
                    index.getSchema().getName(),
                    // INDEX_NAME
                    index.getName(),
                    // TABLE_CATALOG
                    catalog,
                    // TABLE_SCHEMA
                    table.getSchema().getName(),
                    // TABLE_NAME
                    tableName,
                    // COLUMN_NAME
                    idxCol.column.getName(),
                    // ORDINAL_POSITION
                    ValueInteger.get(++i),
                    // ORDERING_SPECIFICATION
                    (sortType & SortOrder.DESCENDING) == 0 ? "ASC" : "DESC",
                    // NULL_ORDERING
                    (sortType & SortOrder.NULLS_FIRST) != 0 ? "FIRST"
                            : (sortType & SortOrder.NULLS_LAST) != 0 ? "LAST" : null
                );
        }
    }

    private void inDoubt(SessionLocal session, ArrayList<Row> rows) {
        if (session.getUser().isAdmin()) {
            ArrayList<InDoubtTransaction> prepared = database.getInDoubtTransactions();
            if (prepared != null) {
                for (InDoubtTransaction prep : prepared) {
                    add(session, rows,
                            // TRANSACTION
                            prep.getTransactionName(),
                            // STATE
                            prep.getStateDescription()
                    );
                }
            }
        }
    }

    private void locks(SessionLocal session, ArrayList<Row> rows) {
        if (session.getUser().isAdmin()) {
            for (SessionLocal s : database.getSessions(false)) {
                locks(session, rows, s);
            }
        } else {
            locks(session, rows, session);
        }
    }

    private void locks(SessionLocal session, ArrayList<Row> rows, SessionLocal sessionWithLocks) {
        for (Table table : sessionWithLocks.getLocks()) {
            add(session, rows,
                    // TABLE_SCHEMA
                    table.getSchema().getName(),
                    // TABLE_NAME
                    table.getName(),
                    // SESSION_ID
                    ValueInteger.get(sessionWithLocks.getId()),
                    // LOCK_TYPE
                    table.isLockedExclusivelyBy(sessionWithLocks) ? "WRITE" : "READ"
            );
        }
    }

    private void queryStatistics(SessionLocal session, ArrayList<Row> rows) {
        QueryStatisticsData control = database.getQueryStatisticsData();
        if (control != null) {
            for (QueryStatisticsData.QueryEntry entry : control.getQueries()) {
                add(session, rows,
                        // SQL_STATEMENT
                        entry.sqlStatement,
                        // EXECUTION_COUNT
                        ValueInteger.get(entry.count),
                        // MIN_EXECUTION_TIME
                        ValueDouble.get(entry.executionTimeMinNanos / 1_000_000d),
                        // MAX_EXECUTION_TIME
                        ValueDouble.get(entry.executionTimeMaxNanos / 1_000_000d),
                        // CUMULATIVE_EXECUTION_TIME
                        ValueDouble.get(entry.executionTimeCumulativeNanos / 1_000_000d),
                        // AVERAGE_EXECUTION_TIME
                        ValueDouble.get(entry.executionTimeMeanNanos / 1_000_000d),
                        // STD_DEV_EXECUTION_TIME
                        ValueDouble.get(entry.getExecutionTimeStandardDeviation() / 1_000_000d),
                        // MIN_ROW_COUNT
                        ValueBigint.get(entry.rowCountMin),
                        // MAX_ROW_COUNT
                        ValueBigint.get(entry.rowCountMax),
                        // CUMULATIVE_ROW_COUNT
                        ValueBigint.get(entry.rowCountCumulative),
                        // AVERAGE_ROW_COUNT
                        ValueDouble.get(entry.rowCountMean),
                        // STD_DEV_ROW_COUNT
                        ValueDouble.get(entry.getRowCountStandardDeviation())
                );
            }
        }
    }

    private void rights(SessionLocal session, Value indexFrom, Value indexTo, ArrayList<Row> rows) {
        if (!session.getUser().isAdmin()) {
            return;
        }
        for (Right r : database.getAllRights()) {
            Role role = r.getGrantedRole();
            DbObject grantee = r.getGrantee();
            String rightType = grantee.getType() == DbObject.USER ? "USER" : "ROLE";
            if (role == null) {
                DbObject object = r.getGrantedObject();
                Schema schema = null;
                Table table = null;
                if (object != null) {
                    if (object instanceof Schema) {
                        schema = (Schema) object;
                    } else if (object instanceof Table) {
                        table = (Table) object;
                        schema = table.getSchema();
                    }
                }
                String tableName = (table != null) ? table.getName() : "";
                String schemaName = (schema != null) ? schema.getName() : "";
                if (!checkIndex(session, tableName, indexFrom, indexTo)) {
                    continue;
                }
                add(session, rows,
                        // GRANTEE
                        identifier(grantee.getName()),
                        // GRANTEETYPE
                        rightType,
                        // GRANTEDROLE
                        "",
                        // RIGHTS
                        r.getRights(),
                        // TABLE_SCHEMA
                        schemaName,
                        // TABLE_NAME
                        tableName
                );
            } else {
                add(session, rows,
                        // GRANTEE
                        identifier(grantee.getName()),
                        // GRANTEETYPE
                        rightType,
                        // GRANTEDROLE
                        identifier(role.getName()),
                        // RIGHTS
                        "",
                        // TABLE_SCHEMA
                        "",
                        // TABLE_NAME
                        ""
                );
            }
        }
    }

    private void roles(SessionLocal session, ArrayList<Row> rows) {
        boolean admin = session.getUser().isAdmin();
        for (Role r : database.getAllRoles()) {
            if (admin || session.getUser().isRoleGranted(r)) {
                add(session, rows,
                        // NAME
                        identifier(r.getName()),
                        // REMARKS
                        r.getComment()
                );
            }
        }
    }

    private void sessions(SessionLocal session, ArrayList<Row> rows) {
        if (session.getUser().isAdmin()) {
            for (SessionLocal s : database.getSessions(false)) {
                sessions(session, rows, s);
            }
        } else {
            sessions(session, rows, session);
        }
    }

    private void sessions(SessionLocal session, ArrayList<Row> rows, SessionLocal s) {
        NetworkConnectionInfo networkConnectionInfo = s.getNetworkConnectionInfo();
        Command command = s.getCurrentCommand();
        int blockingSessionId = s.getBlockingSessionId();
        add(session, rows,
                // ID
                ValueInteger.get(s.getId()),
                // USER_NAME
                s.getUser().getName(),
                // SERVER
                networkConnectionInfo == null ? null : networkConnectionInfo.getServer(),
                // CLIENT_ADDR
                networkConnectionInfo == null ? null : networkConnectionInfo.getClient(),
                // CLIENT_INFO
                networkConnectionInfo == null ? null : networkConnectionInfo.getClientInfo(),
                // SESSION_START
                s.getSessionStart(),
                // ISOLATION_LEVEL
                session.getIsolationLevel().getSQL(),
                // STATEMENT
                command == null ? null : command.toString(),
                // STATEMENT_START
                command == null ? null : s.getCommandStartOrEnd(),
                // CONTAINS_UNCOMMITTED
                ValueBoolean.get(s.containsUncommitted()),
                // STATE
                String.valueOf(s.getState()),
                // BLOCKER_ID
                blockingSessionId == 0 ? null : ValueInteger.get(blockingSessionId),
                // SLEEP_SINCE
                s.getState() == State.SLEEP ? s.getCommandStartOrEnd() : null
        );
    }

    private void sessionState(SessionLocal session, ArrayList<Row> rows) {
        for (String name : session.getVariableNames()) {
            Value v = session.getVariable(name);
            StringBuilder builder = new StringBuilder().append("SET @").append(name).append(' ');
            v.getSQL(builder, DEFAULT_SQL_FLAGS);
            add(session, rows,
                    // KEY
                    "@" + name,
                    // SQL
                    builder.toString()
            );
        }
        for (Table table : session.getLocalTempTables()) {
            add(session, rows,
                    // KEY
                    "TABLE " + table.getName(),
                    // SQL
                    table.getCreateSQL()
            );
        }
        String[] path = session.getSchemaSearchPath();
        if (path != null && path.length > 0) {
            StringBuilder builder = new StringBuilder("SET SCHEMA_SEARCH_PATH ");
            for (int i = 0, l = path.length; i < l; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                StringUtils.quoteIdentifier(builder, path[i]);
            }
            add(session, rows,
                    // KEY
                    "SCHEMA_SEARCH_PATH",
                    // SQL
                    builder.toString()
            );
        }
        String schema = session.getCurrentSchemaName();
        if (schema != null) {
            add(session, rows,
                    // KEY
                    "SCHEMA",
                    // SQL
                    StringUtils.quoteIdentifier(new StringBuilder("SET SCHEMA "), schema).toString()
            );
        }
        TimeZoneProvider currentTimeZone = session.currentTimeZone();
        if (!currentTimeZone.equals(DateTimeUtils.getTimeZone())) {
            add(session, rows,
                    // KEY
                    "TIME ZONE",
                    // SQL
                    StringUtils.quoteStringSQL(new StringBuilder("SET TIME ZONE "), currentTimeZone.getId())
                            .toString()
            );
        }
    }

    private void settings(SessionLocal session, ArrayList<Row> rows) {
        for (Setting s : database.getAllSettings()) {
            String value = s.getStringValue();
            if (value == null) {
                value = Integer.toString(s.getIntValue());
            }
            add(session, rows, identifier(s.getName()), value);
        }
        add(session, rows, "info.BUILD_ID", "" + Constants.BUILD_ID);
        add(session, rows, "info.VERSION_MAJOR", "" + Constants.VERSION_MAJOR);
        add(session, rows, "info.VERSION_MINOR", "" + Constants.VERSION_MINOR);
        add(session, rows, "info.VERSION", Constants.FULL_VERSION);
        if (session.getUser().isAdmin()) {
            String[] settings = {
                    "java.runtime.version", "java.vm.name",
                    "java.vendor", "os.name", "os.arch", "os.version",
                    "sun.os.patch.level", "file.separator",
                    "path.separator", "line.separator", "user.country",
                    "user.language", "user.variant", "file.encoding" };
            for (String s : settings) {
                add(session, rows, "property." + s, Utils.getProperty(s, ""));
            }
        }
        add(session, rows, "DEFAULT_NULL_ORDERING", database.getDefaultNullOrdering().name());
        add(session, rows, "EXCLUSIVE", database.getExclusiveSession() == null ? "FALSE" : "TRUE");
        add(session, rows, "MODE", database.getMode().getName());
        add(session, rows, "QUERY_TIMEOUT", Integer.toString(session.getQueryTimeout()));
        add(session, rows, "TIME ZONE", session.currentTimeZone().getId());
        add(session, rows, "TRUNCATE_LARGE_LENGTH", session.isTruncateLargeLength() ? "TRUE" : "FALSE");
        add(session, rows, "VARIABLE_BINARY", session.isVariableBinary() ? "TRUE" : "FALSE");
        add(session, rows, "OLD_INFORMATION_SCHEMA", session.isOldInformationSchema() ? "TRUE" : "FALSE");
        BitSet nonKeywords = session.getNonKeywords();
        if (nonKeywords != null) {
            add(session, rows, "NON_KEYWORDS", Parser.formatNonKeywords(nonKeywords));
        }
        add(session, rows, "RETENTION_TIME", Integer.toString(database.getRetentionTime()));
        add(session, rows, "LOG", Integer.toString(database.getLogMode()));
        // database settings
        for (Map.Entry<String, String> entry : database.getSettings().getSortedSettings()) {
            add(session, rows, entry.getKey(), entry.getValue());
        }
        if (database.isPersistent()) {
            PageStore pageStore = database.getPageStore();
            if (pageStore != null) {
                add(session, rows,
                        "info.FILE_WRITE_TOTAL", Long.toString(pageStore.getWriteCountTotal()));
                add(session, rows,
                        "info.FILE_WRITE", Long.toString(pageStore.getWriteCount()));
                add(session, rows,
                        "info.FILE_READ", Long.toString(pageStore.getReadCount()));
                add(session, rows,
                        "info.PAGE_COUNT", Integer.toString(pageStore.getPageCount()));
                add(session, rows,
                        "info.PAGE_SIZE", Integer.toString(pageStore.getPageSize()));
                add(session, rows,
                        "info.CACHE_MAX_SIZE", Integer.toString(pageStore.getCache().getMaxMemory()));
                add(session, rows,
                        "info.CACHE_SIZE", Integer.toString(pageStore.getCache().getMemory()));
            }
            Store store = database.getStore();
            if (store != null) {
                MVStore mvStore = store.getMvStore();
                FileStore fs = mvStore.getFileStore();
                if (fs != null) {
                    add(session, rows,
                            "info.FILE_WRITE", Long.toString(fs.getWriteCount()));
                    add(session, rows,
                            "info.FILE_WRITE_BYTES", Long.toString(fs.getWriteBytes()));
                    add(session, rows,
                            "info.FILE_READ", Long.toString(fs.getReadCount()));
                    add(session, rows,
                            "info.FILE_READ_BYTES", Long.toString(fs.getReadBytes()));
                    add(session, rows,
                            "info.UPDATE_FAILURE_PERCENT",
                            String.format(Locale.ENGLISH, "%.2f%%", 100 * mvStore.getUpdateFailureRatio()));
                    add(session, rows,
                            "info.FILL_RATE", Integer.toString(mvStore.getFillRate()));
                    add(session, rows,
                            "info.CHUNKS_FILL_RATE", Integer.toString(mvStore.getChunksFillRate()));
                    add(session, rows,
                            "info.CHUNKS_FILL_RATE_RW", Integer.toString(mvStore.getRewritableChunksFillRate()));
                    try {
                        add(session, rows,
                                "info.FILE_SIZE", Long.toString(fs.getFile().size()));
                    } catch (IOException ignore) {/**/}
                    add(session, rows,
                            "info.CHUNK_COUNT", Long.toString(mvStore.getChunkCount()));
                    add(session, rows,
                            "info.PAGE_COUNT", Long.toString(mvStore.getPageCount()));
                    add(session, rows,
                            "info.PAGE_COUNT_LIVE", Long.toString(mvStore.getLivePageCount()));
                    add(session, rows,
                            "info.PAGE_SIZE", Integer.toString(mvStore.getPageSplitSize()));
                    add(session, rows,
                            "info.CACHE_MAX_SIZE", Integer.toString(mvStore.getCacheSize()));
                    add(session, rows,
                            "info.CACHE_SIZE", Integer.toString(mvStore.getCacheSizeUsed()));
                    add(session, rows,
                            "info.CACHE_HIT_RATIO", Integer.toString(mvStore.getCacheHitRatio()));
                    add(session, rows, "info.TOC_CACHE_HIT_RATIO",
                            Integer.toString(mvStore.getTocCacheHitRatio()));
                    add(session, rows,
                            "info.LEAF_RATIO", Integer.toString(mvStore.getLeafRatio()));
                }
            }
        }
    }

    private void synonyms(SessionLocal session, ArrayList<Row> rows, String catalog) {
        for (TableSynonym synonym : database.getAllSynonyms()) {
            add(session, rows,
                    // SYNONYM_CATALOG
                    catalog,
                    // SYNONYM_SCHEMA
                    synonym.getSchema().getName(),
                    // SYNONYM_NAME
                    synonym.getName(),
                    // SYNONYM_FOR
                    synonym.getSynonymForName(),
                    // SYNONYM_FOR_SCHEMA
                    synonym.getSynonymForSchema().getName(),
                    // TYPE NAME
                    "SYNONYM",
                    // STATUS
                    "VALID",
                    // REMARKS
                    synonym.getComment()
            );
        }
    }

    private void users(SessionLocal session, ArrayList<Row> rows) {
        User currentUser = session.getUser();
        if (currentUser.isAdmin()) {
            for (User u : database.getAllUsers()) {
                users(session, rows, u);
            }
        } else {
            users(session, rows, currentUser);
        }
    }

    private void users(SessionLocal session, ArrayList<Row> rows, User user) {
        add(session, rows,
                // NAME
                identifier(user.getName()),
                // ADMIN
                ValueBoolean.get(user.isAdmin()),
                // REMARKS
                user.getComment()
        );
    }

    private void addConstraintColumnUsage(SessionLocal session, ArrayList<Row> rows, String catalog,
            Constraint constraint, Column column) {
        Table table = column.getTable();
        add(session, rows,
                // TABLE_CATALOG
                catalog,
                // TABLE_SCHEMA
                table.getSchema().getName(),
                // TABLE_NAME
                table.getName(),
                // COLUMN_NAME
                column.getName(),
                // CONSTRAINT_CATALOG
                catalog,
                // CONSTRAINT_SCHEMA
                constraint.getSchema().getName(),
                // CONSTRAINT_NAME
                constraint.getName()
        );
    }

    private void addPrivileges(SessionLocal session, ArrayList<Row> rows, DbObject grantee, String catalog, //
            Table table, String column, int rightMask) {
        if ((rightMask & Right.SELECT) != 0) {
            addPrivilege(session, rows, grantee, catalog, table, column, "SELECT");
        }
        if ((rightMask & Right.INSERT) != 0) {
            addPrivilege(session, rows, grantee, catalog, table, column, "INSERT");
        }
        if ((rightMask & Right.UPDATE) != 0) {
            addPrivilege(session, rows, grantee, catalog, table, column, "UPDATE");
        }
        if ((rightMask & Right.DELETE) != 0) {
            addPrivilege(session, rows, grantee, catalog, table, column, "DELETE");
        }
    }

    private void addPrivilege(SessionLocal session, ArrayList<Row> rows, DbObject grantee, String catalog, Table table,
            String column, String right) {
        String isGrantable = "NO";
        if (grantee.getType() == DbObject.USER) {
            User user = (User) grantee;
            if (user.isAdmin()) {
                // the right is grantable if the grantee is an admin
                isGrantable = "YES";
            }
        }
        if (column == null) {
            add(session, rows,
                    // GRANTOR
                    null,
                    // GRANTEE
                    identifier(grantee.getName()),
                    // TABLE_CATALOG
                    catalog,
                    // TABLE_SCHEMA
                    table.getSchema().getName(),
                    // TABLE_NAME
                    table.getName(),
                    // PRIVILEGE_TYPE
                    right,
                    // IS_GRANTABLE
                    isGrantable,
                    // WITH_HIERARCHY
                    "NO"
            );
        } else {
            add(session, rows,
                    // GRANTOR
                    null,
                    // GRANTEE
                    identifier(grantee.getName()),
                    // TABLE_CATALOG
                    catalog,
                    // TABLE_SCHEMA
                    table.getSchema().getName(),
                    // TABLE_NAME
                    table.getName(),
                    // COLUMN_NAME
                    column,
                    // PRIVILEGE_TYPE
                    right,
                    // IS_GRANTABLE
                    isGrantable
            );
        }
    }

    @Override
    public long getMaxDataModificationId() {
        switch (type) {
        case SETTINGS:
        case SEQUENCES:
        case IN_DOUBT:
        case SESSIONS:
        case LOCKS:
        case SESSION_STATE:
            return Long.MAX_VALUE;
        }
        return database.getModificationDataId();
    }

    @Override
    public boolean isView() {
        return isView;
    }

    @Override
    public long getRowCount(SessionLocal session) {
        return getRowCount(session, false);
    }

    @Override
    public long getRowCountApproximation(SessionLocal session) {
        return getRowCount(session, true);
    }

    private long getRowCount(SessionLocal session, boolean approximation) {
        switch (type) {
        case INFORMATION_SCHEMA_CATALOG_NAME:
            return 1L;
        case COLLATIONS: {
            Locale[] locales = CompareMode.getCollationLocales(approximation);
            if (locales != null) {
                return locales.length + 1;
            }
            break;
        }
        case SCHEMATA:
            return session.getDatabase().getAllSchemas().size();
        case IN_DOUBT:
            if (session.getUser().isAdmin()) {
                ArrayList<InDoubtTransaction> inDoubt = session.getDatabase().getInDoubtTransactions();
                if (inDoubt != null) {
                    return inDoubt.size();
                }
            }
            return 0L;
        case ROLES:
            if (session.getUser().isAdmin()) {
                return session.getDatabase().getAllRoles().size();
            }
            break;
        case SESSIONS:
            if (session.getUser().isAdmin()) {
                return session.getDatabase().getSessionCount();
            } else {
                return 1L;
            }
        case USERS:
            if (session.getUser().isAdmin()) {
                return session.getDatabase().getAllUsers().size();
            } else {
                return 1L;
            }
        }
        if (approximation) {
            return ROW_COUNT_APPROXIMATION;
        }
        throw DbException.throwInternalError(toString());
    }

    @Override
    public boolean canGetRowCount(SessionLocal session) {
        switch (type) {
        case INFORMATION_SCHEMA_CATALOG_NAME:
        case COLLATIONS:
        case SCHEMATA:
        case IN_DOUBT:
        case SESSIONS:
        case USERS:
            return true;
        case ROLES:
            if (session.getUser().isAdmin()) {
                return true;
            }
            break;
        }
        return false;
    }

    /**
     * Data type information.
     */
    static final class DataTypeInformation {

        static final DataTypeInformation NULL = new DataTypeInformation(null, null, null, null, null, null, null, null,
                null, false, null, null, null, null, null);

        /**
         * DATA_TYPE.
         */
        final String dataType;

        /**
         * CHARACTER_MAXIMUM_LENGTH and CHARACTER_OCTET_LENGTH.
         */
        final Value characterPrecision;

        /**
         * NUMERIC_PRECISION.
         */
        final Value numericPrecision;

        /**
         * NUMERIC_PRECISION_RADIX.
         */
        final Value numericPrecisionRadix;

        /**
         * NUMERIC_SCALE.
         */
        final Value numericScale;

        /**
         * DATETIME_PRECISION.
         */
        final Value datetimePrecision;

        /**
         * INTERVAL_PRECISION.
         */
        final Value intervalPrecision;

        /**
         * INTERVAL_TYPE.
         */
        final Value intervalType;

        /**
         * MAXIMUM_CARDINALITY.
         */
        final Value maximumCardinality;

        final boolean hasCharsetAndCollation;

        /**
         * DECLARED_DATA_TYPE.
         */
        final String declaredDataType;

        /**
         * DECLARED_NUMERIC_PRECISION.
         */
        final Value declaredNumericPrecision;

        /**
         * DECLARED_NUMERIC_SCALE.
         */
        final Value declaredNumericScale;

        /**
         * GEOMETRY_TYPE.
         */
        final String geometryType;

        /**
         * GEOMETRY_SRID.
         */
        final Value geometrySrid;

        static DataTypeInformation valueOf(TypeInfo typeInfo) {
            int type = typeInfo.getValueType();
            String dataType = Value.getTypeName(type);
            ValueBigint characterPrecision = null;
            ValueInteger numericPrecision = null, numericScale = null, numericPrecisionRadix = null,
                    datetimePrecision = null, intervalPrecision = null, maximumCardinality = null;
            String intervalType = null;
            boolean hasCharsetAndCollation = false;
            String declaredDataType = null;
            ValueInteger declaredNumericPrecision = null, declaredNumericScale = null;
            String geometryType = null;
            ValueInteger geometrySrid = null;
            switch (type) {
            case Value.CHAR:
            case Value.VARCHAR:
            case Value.CLOB:
            case Value.VARCHAR_IGNORECASE:
                hasCharsetAndCollation = true;
                //$FALL-THROUGH$
            case Value.BINARY:
            case Value.VARBINARY:
            case Value.BLOB:
            case Value.JAVA_OBJECT:
            case Value.JSON:
                characterPrecision = ValueBigint.get(typeInfo.getPrecision());
                break;
            case Value.TINYINT:
            case Value.SMALLINT:
            case Value.INTEGER:
            case Value.BIGINT:
                numericPrecision = ValueInteger.get(MathUtils.convertLongToInt(typeInfo.getPrecision()));
                numericScale = ValueInteger.get(0);
                numericPrecisionRadix = ValueInteger.get(2);
                declaredDataType = dataType;
                break;
            case Value.NUMERIC: {
                numericPrecision = ValueInteger.get(MathUtils.convertLongToInt(typeInfo.getPrecision()));
                numericScale = ValueInteger.get(typeInfo.getScale());
                numericPrecisionRadix = ValueInteger.get(10);
                declaredDataType = typeInfo.getExtTypeInfo() != null ? "DECIMAL" : "NUMERIC";
                if (typeInfo.getDeclaredPrecision() >= 0L) {
                    declaredNumericPrecision = numericPrecision;
                }
                if (typeInfo.getDeclaredScale() != Integer.MIN_VALUE) {
                    declaredNumericScale = numericScale;
                }
                break;
            }
            case Value.REAL:
            case Value.DOUBLE: {
                numericPrecision = ValueInteger.get(MathUtils.convertLongToInt(typeInfo.getPrecision()));
                numericPrecisionRadix = ValueInteger.get(2);
                long declaredPrecision = typeInfo.getDeclaredPrecision();
                if (declaredPrecision >= 0) {
                    declaredDataType = "FLOAT";
                    if (declaredPrecision > 0) {
                        declaredNumericPrecision = ValueInteger.get((int) declaredPrecision);
                    }
                } else {
                    declaredDataType = dataType;
                }
                break;
            }
            case Value.DECFLOAT:
                numericPrecision = ValueInteger.get(MathUtils.convertLongToInt(typeInfo.getPrecision()));
                numericPrecisionRadix = ValueInteger.get(10);
                declaredDataType = dataType;
                if (typeInfo.getDeclaredPrecision() >= 0L) {
                    declaredNumericPrecision = numericPrecision;
                }
                break;
            case Value.INTERVAL_YEAR:
            case Value.INTERVAL_MONTH:
            case Value.INTERVAL_DAY:
            case Value.INTERVAL_HOUR:
            case Value.INTERVAL_MINUTE:
            case Value.INTERVAL_SECOND:
            case Value.INTERVAL_YEAR_TO_MONTH:
            case Value.INTERVAL_DAY_TO_HOUR:
            case Value.INTERVAL_DAY_TO_MINUTE:
            case Value.INTERVAL_DAY_TO_SECOND:
            case Value.INTERVAL_HOUR_TO_MINUTE:
            case Value.INTERVAL_HOUR_TO_SECOND:
            case Value.INTERVAL_MINUTE_TO_SECOND:
                intervalType = IntervalQualifier.valueOf(type - Value.INTERVAL_YEAR).toString();
                dataType = "INTERVAL";
                intervalPrecision = ValueInteger.get(MathUtils.convertLongToInt(typeInfo.getPrecision()));
                //$FALL-THROUGH$
            case Value.DATE:
            case Value.TIME:
            case Value.TIME_TZ:
            case Value.TIMESTAMP:
            case Value.TIMESTAMP_TZ:
                datetimePrecision = ValueInteger.get(typeInfo.getScale());
                break;
            case Value.GEOMETRY: {
                ExtTypeInfoGeometry extTypeInfo = (ExtTypeInfoGeometry) typeInfo.getExtTypeInfo();
                if (extTypeInfo != null) {
                    int typeCode = extTypeInfo.getType();
                    if (typeCode != 0) {
                        geometryType = EWKTUtils.formatGeometryTypeAndDimensionSystem(new StringBuilder(), typeCode)
                                .toString();
                    }
                    Integer srid = extTypeInfo.getSrid();
                    if (srid != null) {
                        geometrySrid = ValueInteger.get(srid);
                    }
                }
                break;
            }
            case Value.ARRAY:
                maximumCardinality = ValueInteger.get(MathUtils.convertLongToInt(typeInfo.getPrecision()));
            }
            return new DataTypeInformation(dataType, characterPrecision, numericPrecision, numericPrecisionRadix,
                    numericScale, datetimePrecision, intervalPrecision,
                    intervalType != null ? ValueVarchar.get(intervalType) : ValueNull.INSTANCE, maximumCardinality,
                    hasCharsetAndCollation, declaredDataType, declaredNumericPrecision, declaredNumericScale,
                    geometryType, geometrySrid);
        }

        private DataTypeInformation(String dataType, Value characterPrecision, Value numericPrecision,
                Value numericPrecisionRadix, Value numericScale, Value datetimePrecision, Value intervalPrecision,
                Value intervalType, Value maximumCardinality, boolean hasCharsetAndCollation, String declaredDataType,
                Value declaredNumericPrecision, Value declaredNumericScale, String geometryType, Value geometrySrid) {
            this.dataType = dataType;
            this.characterPrecision = characterPrecision;
            this.numericPrecision = numericPrecision;
            this.numericPrecisionRadix = numericPrecisionRadix;
            this.numericScale = numericScale;
            this.datetimePrecision = datetimePrecision;
            this.intervalPrecision = intervalPrecision;
            this.intervalType = intervalType;
            this.maximumCardinality = maximumCardinality;
            this.hasCharsetAndCollation = hasCharsetAndCollation;
            this.declaredDataType = declaredDataType;
            this.declaredNumericPrecision = declaredNumericPrecision;
            this.declaredNumericScale = declaredNumericScale;
            this.geometryType = geometryType;
            this.geometrySrid = geometrySrid;
        }

    }

}
