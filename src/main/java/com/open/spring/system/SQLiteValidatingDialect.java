package com.open.spring.system;

import java.sql.Types;

import org.hibernate.community.dialect.SQLiteDialect;

/**
 * SQLiteDialect whose schema validation understands SQLite's single 64-bit integer
 * storage class. Hibernate emits {@code integer} for identity primary keys on SQLite
 * (the only spelling that makes a rowid alias) yet validates the same {@code Long}
 * field as {@code bigint}; without this every {@code @GeneratedValue(IDENTITY)} entity
 * would fail {@code ddl-auto=validate} even on a database Hibernate itself created.
 * Only the integer family is widened: a {@code TEXT} column holding a {@code Long}
 * still fails validation, which is the drift we want to catch.
 */
public class SQLiteValidatingDialect extends SQLiteDialect {

    private static boolean integerFamily(int typeCode) {
        return typeCode == Types.INTEGER || typeCode == Types.BIGINT
                || typeCode == Types.SMALLINT || typeCode == Types.TINYINT;
    }


    @Override
    public boolean equivalentTypes(int typeCode1, int typeCode2) {
        if (integerFamily(typeCode1) && integerFamily(typeCode2)) {
            return true;
        }
        return super.equivalentTypes(typeCode1, typeCode2);
    }
}
