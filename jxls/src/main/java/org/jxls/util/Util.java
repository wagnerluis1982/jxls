package org.jxls.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jxls.common.CellRef;
import org.jxls.common.CellRefColPrecedenceComparator;
import org.jxls.common.CellRefRowPrecedenceComparator;
import org.jxls.common.Context;
import org.jxls.common.GroupData;
import org.jxls.common.JxlsException;
import org.jxls.expression.EvaluationException;
import org.jxls.expression.ExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class with various helper methods used by other classes
 *
 * @author Leonid Vysochyn
 * @since 2/3/12
 */
public class Util {
    private static Logger logger = LoggerFactory.getLogger(Util.class);
    public static final String regexJointedLookBehind = "(?<!U_\\([^)]{0,100})";
    public static final String regexSimpleCellRef = "[a-zA-Z]+[0-9]+";
    public static final String regexCellRef = "([a-zA-Z]+[a-zA-Z0-9]*![a-zA-Z]+[0-9]+|(?<!\\d)[a-zA-Z]+[0-9]+|'[^?\\\\/:'*]+'![a-zA-Z]+[0-9]+)";
    public static final String regexAreaRef = regexCellRef + ":" + regexSimpleCellRef;
    public static final Pattern regexAreaRefPattern = Pattern.compile(regexAreaRef);
    public static final String regexCellRefExcludingJointed = regexJointedLookBehind + regexCellRef;
    private static final Pattern regexCellRefExcludingJointedPattern = Pattern.compile(regexCellRefExcludingJointed);
    private static final Pattern regexCellRefPattern = Pattern.compile(regexCellRef);
    public static final String regexJointedCellRef = "U_\\([^\\)]+\\)";
    private static final Pattern regexJointedCellRefPattern = Pattern.compile(regexJointedCellRef);
    public static final String regexExcludePrefixSymbols = "(?<!\\w)";

    /**
     * Parses a formula and returns a list of cell names used in it
     * E.g. for formula "B4*(1+C4)" the returned list will contain "B4", "C4"
     * @param formula string
     * @return a list of cell names used in the formula
     */
    public static List<String> getFormulaCellRefs(String formula) {
        return getStringPartsByPattern(formula, regexCellRefExcludingJointedPattern);
    }

    private static List<String> getStringPartsByPattern(String str, Pattern pattern) {
        List<String> cellRefs = new ArrayList<String>();
        if (str != null) {
            Matcher cellRefMatcher = pattern.matcher(str);
            while (cellRefMatcher.find()) {
                cellRefs.add(cellRefMatcher.group());
            }
        }
        return cellRefs;
    }

    /**
     * Parses a formula to extract a list of so called "jointed cells"
     * The jointed cells are cells combined with a special notation "U_(cell1, cell2)" into a single cell
     * They are used in formulas like this "$[SUM(U_(F8,F13))]".
     * Here the formula will use both F8 and F13 source cells to calculate the sum
     * @param formula a formula string to parse
     * @return a list of jointed cells used in the formula
     */
    public static List<String> getJointedCellRefs(String formula) {
        return getStringPartsByPattern(formula, regexJointedCellRefPattern);
    }

    /**
     * Parses a "jointed cell" reference and extracts individual cell references
     * @param jointedCellRef a jointed cell reference to parse
     * @return a list of cell names extracted from the jointed cell reference
     */
    public static List<String> getCellRefsFromJointedCellRef(String jointedCellRef) {
        return getStringPartsByPattern(jointedCellRef, regexCellRefPattern);
    }

    public static boolean formulaContainsJointedCellRef(String formula) {
        return regexJointedCellRefPattern.matcher(formula).find();
    }

    public static String createTargetCellRef(List<CellRef> targetCellDataList) {
        String resultRef = "";
        if (targetCellDataList == null || targetCellDataList.isEmpty()) {
            return resultRef;
        }
        List<String> cellRefs = new ArrayList<String>();
        boolean rowRange = true;
        boolean colRange = true;
        Iterator<CellRef> iterator = targetCellDataList.iterator();
        CellRef firstCellRef = iterator.next();
        cellRefs.add(firstCellRef.getCellName());
        String sheetName = firstCellRef.getSheetName();
        int row = firstCellRef.getRow();
        int col = firstCellRef.getCol();
        while (iterator.hasNext()) {
            CellRef cellRef = iterator.next();
            if ((rowRange || colRange) && !cellRef.getSheetName().equals(sheetName)) {
                rowRange = false;
                colRange = false;
            }
            if (rowRange && !(cellRef.getRow() - row == 1 && cellRef.getCol() == col)) {
                rowRange = false;
            }
            if (colRange && !(cellRef.getCol() - col == 1 && cellRef.getRow() == row)) {
                colRange = false;
            }
            sheetName = cellRef.getSheetName();
            row = cellRef.getRow();
            col = cellRef.getCol();
            cellRefs.add(cellRef.getCellName());
        }
        if ((rowRange || colRange) && cellRefs.size() > 1) {
            resultRef = cellRefs.get(0) + ":" + cellRefs.get(cellRefs.size() - 1);
        } else {
            resultRef = joinStrings(cellRefs, ",");
        }
        return resultRef;
    }

    public static String joinStrings(List<String> strings, String separator) {
        StringBuilder sb = new StringBuilder();
        String sep = "";
        for (String s : strings) {
            sb.append(sep).append(s);
            sep = separator;
        }
        return sb.toString();
    }

    /**
     * Groups a list of cell references into a list ranges which can be used in a formula substitution
     * @param cellRefList a list of cell references
     * @param targetRangeCount a number of ranges to use when grouping
     * @return a list of cell ranges grouped by row or by column
     */
    public static List<List<CellRef>> groupByRanges(List<CellRef> cellRefList, int targetRangeCount) {
        List<List<CellRef>> colRanges = groupByColRange(cellRefList);
        if (targetRangeCount == 0 || colRanges.size() == targetRangeCount) {
            return colRanges;
        }
        List<List<CellRef>> rowRanges = groupByRowRange(cellRefList);
        if (rowRanges.size() == targetRangeCount) {
            return rowRanges;
        } else {
            return colRanges;
        }
    }

    public static List<List<CellRef>> groupByColRange(List<CellRef> cellRefList) {
        List<List<CellRef>> rangeList = new ArrayList<List<CellRef>>();
        if (cellRefList == null || cellRefList.size() == 0) {
            return rangeList;
        }
        List<CellRef> cellRefListCopy = new ArrayList<CellRef>(cellRefList);
        Collections.sort(cellRefListCopy, new CellRefColPrecedenceComparator());

        String sheetName = cellRefListCopy.get(0).getSheetName();
        int row = cellRefListCopy.get(0).getRow();
        int col = cellRefListCopy.get(0).getCol();
        List<CellRef> currentRange = new ArrayList<CellRef>();
        currentRange.add(cellRefListCopy.get(0));
        boolean rangeComplete = false;
        for (int i = 1; i < cellRefListCopy.size(); i++) {
            CellRef cellRef = cellRefListCopy.get(i);
            if (!cellRef.getSheetName().equals(sheetName)) {
                rangeComplete = true;
            } else {
                int rowDelta = cellRef.getRow() - row;
                int colDelta = cellRef.getCol() - col;
                if (rowDelta == 1 && colDelta == 0) {
                    currentRange.add(cellRef);
                } else {
                    rangeComplete = true;
                }
            }
            sheetName = cellRef.getSheetName();
            row = cellRef.getRow();
            col = cellRef.getCol();
            if (rangeComplete) {
                rangeList.add(currentRange);
                currentRange = new ArrayList<CellRef>();
                currentRange.add(cellRef);
                rangeComplete = false;
            }
        }
        rangeList.add(currentRange);
        return rangeList;
    }

    public static List<List<CellRef>> groupByRowRange(List<CellRef> cellRefList) {
        List<List<CellRef>> rangeList = new ArrayList<List<CellRef>>();
        if (cellRefList == null || cellRefList.size() == 0) {
            return rangeList;
        }
        List<CellRef> cellRefListCopy = new ArrayList<CellRef>(cellRefList);
        Collections.sort(cellRefListCopy, new CellRefRowPrecedenceComparator());

        String sheetName = cellRefListCopy.get(0).getSheetName();
        int row = cellRefListCopy.get(0).getRow();
        int col = cellRefListCopy.get(0).getCol();
        List<CellRef> currentRange = new ArrayList<CellRef>();
        currentRange.add(cellRefListCopy.get(0));
        boolean rangeComplete = false;
        for (int i = 1; i < cellRefListCopy.size(); i++) {
            CellRef cellRef = cellRefListCopy.get(i);
            if (!cellRef.getSheetName().equals(sheetName)) {
                rangeComplete = true;
            } else {
                int rowDelta = cellRef.getRow() - row;
                int colDelta = cellRef.getCol() - col;
                if (colDelta == 1 && rowDelta == 0) {
                    currentRange.add(cellRef);
                } else {
                    rangeComplete = true;
                }
            }
            sheetName = cellRef.getSheetName();
            row = cellRef.getRow();
            col = cellRef.getCol();
            if (rangeComplete) {
                rangeList.add(currentRange);
                currentRange = new ArrayList<CellRef>();
                currentRange.add(cellRef);
                rangeComplete = false;
            }
        }
        rangeList.add(currentRange);
        return rangeList;
    }

    public static Boolean isConditionTrue(ExpressionEvaluator evaluator, String condition, Context context) {
        Object conditionResult = evaluator.evaluate(condition, context.toMap());
        if (!(conditionResult instanceof Boolean)) {
            throw new JxlsException("Condition result is not a boolean value - " + condition);
        }
        return (Boolean) conditionResult;
    }

    public static Boolean isConditionTrue(ExpressionEvaluator evaluator, Context context) {
        Object conditionResult = evaluator.evaluate(context.toMap());
        if (!(conditionResult instanceof Boolean)) {
            throw new EvaluationException("Condition result is not a boolean value - " + evaluator.getExpression());
        }
        return (Boolean) conditionResult;
    }

    public static void setObjectProperty(Object obj, String propertyName, String propertyValue, boolean ignoreNonExisting) {
        try {
            setObjectProperty(obj, propertyName, propertyValue);
        } catch (Exception e) {
            String msg = "failed to set property '" + propertyName + "' to value '" + propertyValue + "' for object " + obj;
            if (ignoreNonExisting) {
                logger.info(msg, e);
            } else {
                logger.warn(msg);
                throw new IllegalArgumentException(e);
            }
        }
    }

    public static void setObjectProperty(Object obj, String propertyName, String propertyValue)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        String name = "set" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        Method method = obj.getClass().getMethod(name, new Class[] { String.class });
        method.invoke(obj, propertyValue);
    }

    public static Object getObjectProperty(Object obj, String propertyName, boolean failSilently) {
        try {
            return getObjectProperty(obj, propertyName);
        } catch (Exception e) {
            String msg = "failed to get property '" + propertyName + "' of object " + obj;
            if (failSilently) {
                logger.info(msg, e);
                return null;
            } else {
                logger.warn(msg);
                throw new IllegalArgumentException(e);
            }
        }
    }

    public static Object getObjectProperty(Object obj, String propertyName) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (obj instanceof Map) {
            return ((Map<?, ?>) obj).get(propertyName);
        }
        String name = "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        Method method = obj.getClass().getMethod(name);
        return method.invoke(obj);
    }

    // TODO MW: groupCollection vs. groupIterable: duplicate code?
    public static Collection<GroupData> groupCollection(Collection<?> collection, String groupProperty, String groupOrder) {
        Collection<GroupData> result = new ArrayList<GroupData>();
        if (collection == null) {
            return result;
        }
        Set<Object> groupByValues;
        if (groupOrder != null) {
            if ("desc".equalsIgnoreCase(groupOrder)) {
                groupByValues = new TreeSet<>(Collections.reverseOrder());
            } else {
                groupByValues = new TreeSet<>();
            }
        } else {
            groupByValues = new LinkedHashSet<>();
        }
        for (Object bean : collection) {
            groupByValues.add(getGroupKey(bean, groupProperty));
        }
        for (Iterator<Object> iterator = groupByValues.iterator(); iterator.hasNext();) {
            Object groupValue = iterator.next();
            List<Object> groupItems = new ArrayList<>();
            for (Object bean : collection) {
                if (groupValue.equals(getGroupKey(bean, groupProperty))) {
                    groupItems.add(bean);
                }
            }
            if (!groupItems.isEmpty()) {
                result.add(new GroupData(groupItems.get(0), groupItems));
            }
        }
        return result;
    }

    public static Collection<GroupData> groupIterable(Iterable<?> iterable, String groupProperty, String groupOrder) {
        Collection<GroupData> result = new ArrayList<GroupData>();
        if (iterable == null) {
            return result;
        }
        Set<Object> groupByValues;
        if (groupOrder != null) {
            if ("desc".equalsIgnoreCase(groupOrder)) {
                groupByValues = new TreeSet<>(Collections.reverseOrder());
            } else {
                groupByValues = new TreeSet<>();
            }
        } else {
            groupByValues = new LinkedHashSet<>();
        }
        for (Object bean : iterable) {
            groupByValues.add(getGroupKey(bean, groupProperty));
        }
        for (Iterator<Object> iterator = groupByValues.iterator(); iterator.hasNext();) {
            Object groupValue = iterator.next();
            List<Object> groupItems = new ArrayList<>();
            for (Object bean : iterable) {
                if (groupValue.equals(getGroupKey(bean, groupProperty))) {
                    groupItems.add(bean);
                }
            }
            if (!groupItems.isEmpty()) {
                result.add(new GroupData(groupItems.get(0), groupItems));
            }
        }
        return result;
    }

    private static Object getGroupKey(Object bean, String propertyName) {
        Object ret = getObjectProperty(bean, propertyName, true);
        return ret == null ? "null" : ret;
    }

    /**
     * Reads all the data from the input stream, and returns the bytes read.
     * 
     * @param stream -
     * @return byte array
     * @throws IOException -
     */
    public static byte[] toByteArray(InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read = 0;
        while (read != -1) {
            read = stream.read(buffer);
            if (read > 0) {
                baos.write(buffer, 0, read);
            }
        }
        return baos.toByteArray();
    }

    public static Collection<?> transformToCollectionObject(ExpressionEvaluator expressionEvaluator, String collectionName, Context context) {
        Object collectionObject = expressionEvaluator.evaluate(collectionName, context.toMap());
        if (!(collectionObject instanceof Collection)) {
            throw new JxlsException(collectionName + " expression is not a collection");
        }
        return (Collection<?>) collectionObject;
    }

    public static String sheetNameRegex(Map.Entry<CellRef, List<CellRef>> cellRefEntry) {
        return (cellRefEntry.getKey().isIgnoreSheetNameInFormat() ? "(?<!!)" : "");
    }

    public static List<CellRef> createTargetCellRefListByColumn(CellRef targetFormulaCellRef, List<CellRef> targetCells,
            List<CellRef> cellRefsToExclude) {
        List<CellRef> resultCellList = new ArrayList<>();
        int col = targetFormulaCellRef.getCol();
        for (CellRef targetCell : targetCells) {
            if (targetCell.getCol() == col
                    && targetCell.getRow() < targetFormulaCellRef.getRow()
                    && !cellRefsToExclude.contains(targetCell)) {
                resultCellList.add(targetCell);
            }
        }
        return resultCellList;
    }

    public static int countOccurences(String str, char ch) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) {
                count++;
            }
        }
        return count;
    }

    public static Iterable<Object> transformToIterableObject(ExpressionEvaluator expressionEvaluator, String collectionName, Context context) {
        Object collectionObject = expressionEvaluator.evaluate(collectionName, context.toMap());
        if (!(collectionObject instanceof Iterable)) {
            throw new JxlsException(collectionName + " expression is not a collection");
        }
        List<Object> ret = new ArrayList<>();
        for (Object i : (Iterable) collectionObject) {
            ret.add(i);
        }
        return ret;
    }

    public static String getStrictCellNameRegex(String name) {
        return "(?<=[^A-Z]|^)" + name + "(?=\\D|$)";
    }
}