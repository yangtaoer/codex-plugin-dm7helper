package io.dm7codex.plugin.sql;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Read-only parser facade used by the console before execution. */
public final class SqlClassificationService {
    private final DmSqlParser parser;
    private final SqlSecurityPolicy security;
    private final int maximumUtf8Bytes;

    public SqlClassificationService(DmSqlParser parser, SqlSecurityPolicy security, int maximumUtf8Bytes) {
        this.parser=Objects.requireNonNull(parser);this.security=Objects.requireNonNull(security);
        if(maximumUtf8Bytes<1)throw new IllegalArgumentException("maximumUtf8Bytes");
        this.maximumUtf8Bytes=maximumUtf8Bytes;
    }

    public Classification classify(String sql) {
        if(sql==null||sql.isBlank())throw new ClassificationRejected("BLANK_SQL");
        if(sql.getBytes(StandardCharsets.UTF_8).length>maximumUtf8Bytes)throw new ClassificationRejected("SQL_TOO_LARGE");
        List<ParsedStatement> statements=parser.parse(sql);
        if(statements.isEmpty())throw new ClassificationRejected("BLANK_SQL");
        statements.forEach(security::assertNoEmbeddedCredentials);
        List<SqlKind> kinds=statements.stream().map(ParsedStatement::kind).toList();
        boolean queryOnly=kinds.stream().allMatch(kind->kind==SqlKind.QUERY||kind==SqlKind.EXPLAIN);
        boolean mutationOnly=kinds.stream().allMatch(kind->kind==SqlKind.DML||kind==SqlKind.DDL);
        if(!queryOnly&&!mutationOnly)throw new ClassificationRejected("UNSUPPORTED_SQL");
        if(queryOnly&&kinds.size()!=1)throw new ClassificationRejected("MULTI_QUERY_UNSUPPORTED");
        boolean atomicAllowed=mutationOnly&&kinds.stream().allMatch(kind->kind==SqlKind.DML);
        return new Classification(kinds.size(),kinds,queryOnly,!queryOnly,atomicAllowed);
    }

    public record Classification(int statementCount,List<SqlKind> kinds,boolean queryOnly,
                                 boolean requiresPurpose,boolean atomicAllowed){
        public Classification{kinds=List.copyOf(kinds);}
        @Override public String toString(){return "Classification[statementCount="+statementCount+", kinds="+kinds
                +", queryOnly="+queryOnly+", requiresPurpose="+requiresPurpose+", atomicAllowed="+atomicAllowed+"]";}
    }
    public static final class ClassificationRejected extends IllegalArgumentException {
        public ClassificationRejected(String safeCode){super(safeCode);}
    }
}
