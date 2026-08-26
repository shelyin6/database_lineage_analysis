package com.xcloud.metadata.model;

import java.util.List;

public record ColumnLineageTreeNode(
        String table,
        String column,
        String status,
        String qualifiedProcedureName,
        String sourceFile,
        int line,
        List<ColumnLineageTreeNode> children
) {
}
