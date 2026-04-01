package com.softwareag.is.wmscript.runtime;

import com.wm.app.b2b.server.Service;
import com.wm.data.*;
import com.wm.lang.ns.NSName;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Runtime helper for generated WmScript code.
 * Wraps the IS pipeline (IData) and provides a clean API
 * for the generated Java code to interact with.
 */
public class PipelineContext {

    private final IData pipeline;

    public PipelineContext(IData pipeline) {
        this.pipeline = pipeline;
    }

    public IData getPipeline() {
        return pipeline;
    }

    // ---- Get / Set / Remove ----

    public Object get(String key) {
        IDataCursor c = pipeline.getCursor();
        try {
            return IDataUtil.get(c, key);
        } finally {
            c.destroy();
        }
    }

    public String getString(String key) {
        Object val = get(key);
        return val != null ? val.toString() : null;
    }

    public IData getDocument(String key) {
        IDataCursor c = pipeline.getCursor();
        try {
            return IDataUtil.getIData(c, key);
        } finally {
            c.destroy();
        }
    }

    public IData[] getDocumentList(String key) {
        IDataCursor c = pipeline.getCursor();
        try {
            return IDataUtil.getIDataArray(c, key);
        } finally {
            c.destroy();
        }
    }

    public void set(String key, Object value) {
        IDataCursor c = pipeline.getCursor();
        try {
            IDataUtil.put(c, key, value);
        } finally {
            c.destroy();
        }
    }

    public void remove(String key) {
        IDataCursor c = pipeline.getCursor();
        try {
            IDataUtil.remove(c, key);
        } finally {
            c.destroy();
        }
    }

    public boolean exists(String key) {
        IDataCursor c = pipeline.getCursor();
        try {
            return c.first(key);
        } finally {
            c.destroy();
        }
    }

    // ---- Nested document access ----

    public static Object getField(Object doc, String field) {
        if (doc instanceof IData) {
            IDataCursor c = ((IData) doc).getCursor();
            try {
                return IDataUtil.get(c, field);
            } finally {
                c.destroy();
            }
        }
        return null;
    }

    public static void setField(Object doc, String field, Object value) {
        if (doc instanceof IData) {
            IDataCursor c = ((IData) doc).getCursor();
            try {
                IDataUtil.put(c, field, value);
            } finally {
                c.destroy();
            }
        }
    }

    /** Null-safe field access: returns null if doc or field is null */
    public static Object getFieldSafe(Object doc, String field) {
        if (doc == null) return null;
        return getField(doc, field);
    }

    /** Navigate a dot-path: getPath(doc, "customer", "address", "city") */
    public static Object getPath(Object root, String... fields) {
        Object current = root;
        for (String field : fields) {
            if (current == null) return null;
            current = getField(current, field);
        }
        return current;
    }

    // ---- Service invocation ----

    /** Invoke an IS service with named arguments */
    public static IData invoke(String serviceName, Object... args) throws Exception {
        IData input = IDataFactory.create();
        IDataCursor c = input.getCursor();
        try {
            for (int i = 0; i < args.length; i += 2) {
                String key = (String) args[i];
                Object val = args[i + 1];
                if (val != null) {
                    IDataUtil.put(c, key, val);
                }
            }
        } finally {
            c.destroy();
        }
        return Service.doInvoke(NSName.create(serviceName), input);
    }

    /** Extract a field from an invoke result */
    public static Object extract(IData result, String field) {
        if (result == null) return null;
        IDataCursor c = result.getCursor();
        try {
            return IDataUtil.get(c, field);
        } finally {
            c.destroy();
        }
    }

    // ---- Type coercion builtins ----

    public static double toNum(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        return Double.parseDouble(val.toString());
    }

    public static int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).intValue();
        return Integer.parseInt(val.toString());
    }

    public static String toStr(Object val) {
        return val != null ? val.toString() : "";
    }

    public static Date toDate(Object val, String format) throws Exception {
        if (val == null) return null;
        if (val instanceof Date) return (Date) val;
        return new SimpleDateFormat(format).parse(val.toString());
    }

    // ---- Collection builtins ----

    public static int len(Object val) {
        if (val == null) return 0;
        if (val instanceof String) return ((String) val).length();
        if (val instanceof Object[]) return ((Object[]) val).length;
        if (val instanceof IData[]) return ((IData[]) val).length;
        if (val instanceof Collection) return ((Collection<?>) val).size();
        if (val instanceof List) return ((List<?>) val).size();
        return 0;
    }

    public static double sum(Object val) {
        if (val == null) return 0.0;
        double total = 0.0;
        if (val instanceof Object[]) {
            for (Object item : (Object[]) val) {
                total += toNum(item);
            }
        }
        return total;
    }

    public static Object min(Object val) {
        if (val == null || !(val instanceof Object[])) return null;
        Object[] arr = (Object[]) val;
        if (arr.length == 0) return null;
        double min = Double.MAX_VALUE;
        for (Object item : arr) {
            double v = toNum(item);
            if (v < min) min = v;
        }
        return min;
    }

    public static Object max(Object val) {
        if (val == null || !(val instanceof Object[])) return null;
        Object[] arr = (Object[]) val;
        if (arr.length == 0) return null;
        double max = Double.MIN_VALUE;
        for (Object item : arr) {
            double v = toNum(item);
            if (v > max) max = v;
        }
        return max;
    }

    public static String join(Object val, String delimiter) {
        if (val == null) return "";
        if (val instanceof String[]) {
            return String.join(delimiter, (String[]) val);
        }
        if (val instanceof Object[]) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ((Object[]) val).length; i++) {
                if (i > 0) sb.append(delimiter);
                sb.append(toStr(((Object[]) val)[i]));
            }
            return sb.toString();
        }
        return toStr(val);
    }

    public static String[] split(Object val, String delimiter) {
        if (val == null) return new String[0];
        return val.toString().split(delimiter);
    }

    public static String[] keys(Object val) {
        if (val == null || !(val instanceof IData)) return new String[0];
        IDataCursor c = ((IData) val).getCursor();
        try {
            List<String> keys = new ArrayList<>();
            while (c.next()) {
                keys.add(c.getKey());
            }
            return keys.toArray(new String[0]);
        } finally {
            c.destroy();
        }
    }

    public static Object[] values(Object val) {
        if (val == null || !(val instanceof IData)) return new Object[0];
        IDataCursor c = ((IData) val).getCursor();
        try {
            List<Object> vals = new ArrayList<>();
            while (c.next()) {
                vals.add(c.getValue());
            }
            return vals.toArray();
        } finally {
            c.destroy();
        }
    }

    /** Return key-value pairs as an IData[] array of {key, value} documents */
    public static IData[] entries(Object val) {
        if (val == null || !(val instanceof IData)) return new IData[0];
        IDataCursor c = ((IData) val).getCursor();
        try {
            List<IData> result = new ArrayList<>();
            while (c.next()) {
                result.add(createDocument("key", c.getKey(), "value", c.getValue()));
            }
            return result.toArray(new IData[0]);
        } finally {
            c.destroy();
        }
    }

    /** Convert to IData[] for two-variable for loops */
    public static IData[] toEntryArray(Object val) {
        if (val instanceof IData[]) return (IData[]) val;
        if (val instanceof IData) return entries(val);
        return new IData[0];
    }

    /** Check if string/array contains a value */
    public static boolean contains(Object container, Object value) {
        if (container == null) return false;
        if (container instanceof String) {
            return value != null && container.toString().contains(value.toString());
        }
        if (container instanceof Object[]) {
            for (Object item : (Object[]) container) {
                if (eq(item, value)) return true;
            }
        }
        if (container instanceof IData[]) {
            for (IData item : (IData[]) container) {
                if (eq(item, value)) return true;
            }
        }
        return false;
    }

    /** Trim whitespace from string */
    public static String trim(Object val) {
        return val != null ? val.toString().trim() : "";
    }

    /** Replace occurrences in string */
    public static String replaceStr(Object val, Object search, Object replacement) {
        if (val == null) return "";
        return val.toString().replace(
            search != null ? search.toString() : "",
            replacement != null ? replacement.toString() : "");
    }

    /** Sort an array (strings lexicographically, numbers numerically) */
    @SuppressWarnings("unchecked")
    public static Object[] sortArray(Object val) {
        if (val == null) return new Object[0];
        if (val instanceof Object[]) {
            Object[] arr = ((Object[]) val).clone();
            java.util.Arrays.sort(arr, (a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return -1;
                if (b == null) return 1;
                try {
                    return Double.compare(toNum(a), toNum(b));
                } catch (Exception e) {
                    return a.toString().compareTo(b.toString());
                }
            });
            return arr;
        }
        return new Object[] { val };
    }

    /**
     * Map: extract a field from each element of an IData array.
     * Usage: map(orders, "customerName") → ["John", "Jane", ...]
     */
    public static Object[] mapArray(Object array, Object fieldName) {
        if (array == null || !(array instanceof IData[])) return new Object[0];
        IData[] arr = (IData[]) array;
        Object[] result = new Object[arr.length];
        String field = toStr(fieldName);
        for (int i = 0; i < arr.length; i++) {
            result[i] = getField(arr[i], field);
        }
        return result;
    }

    /**
     * Filter: keep elements where a field equals a value.
     * Usage: filter(orders, "status", "active") → filtered IData[]
     * Note: for simple field==value filter, use orders[status == "active"] syntax instead
     */
    public static IData[] filterArray(Object array, Object fieldName, Object value) {
        if (array == null || !(array instanceof IData[])) return new IData[0];
        IData[] arr = (IData[]) array;
        String field = toStr(fieldName);
        List<IData> result = new ArrayList<>();
        for (IData item : arr) {
            if (eq(getField(item, field), value)) {
                result.add(item);
            }
        }
        return result.toArray(new IData[0]);
    }

    /**
     * Reduce: accumulate a value across array elements by extracting a field and summing.
     * Usage: reduce(orders, "amount", 0) → total sum
     */
    public static double reduceArray(Object array, Object fieldName, Object initial) {
        if (array == null || !(array instanceof IData[])) return toNum(initial);
        IData[] arr = (IData[]) array;
        String field = toStr(fieldName);
        double acc = toNum(initial);
        for (IData item : arr) {
            acc += toNum(getField(item, field));
        }
        return acc;
    }

    public static String typeOf(Object val) {
        if (val == null) return "null";
        if (val instanceof String) return "string";
        if (val instanceof Number) return "number";
        if (val instanceof Boolean) return "boolean";
        if (val instanceof IData) return "document";
        if (val instanceof IData[]) return "documentList";
        if (val instanceof String[]) return "stringList";
        if (val instanceof Object[]) return "list";
        if (val instanceof Date) return "date";
        if (val instanceof byte[]) return "bytes";
        return val.getClass().getSimpleName();
    }

    // ---- Array operations ----

    /** Project a field from each IData in an array: orders[].id */
    public static Object[] project(Object array, String field) {
        if (array == null) return new Object[0];
        if (array instanceof IData[]) {
            IData[] arr = (IData[]) array;
            Object[] result = new Object[arr.length];
            for (int i = 0; i < arr.length; i++) {
                result[i] = getField(arr[i], field);
            }
            return result;
        }
        return new Object[0];
    }

    /** Filter an IData array by field value: orders[status == "active"] */
    public static IData[] filter(Object array, String field, Object value) {
        if (array == null || !(array instanceof IData[])) return new IData[0];
        IData[] arr = (IData[]) array;
        List<IData> result = new ArrayList<>();
        for (IData item : arr) {
            Object fieldVal = getField(item, field);
            if (eq(value, fieldVal)) {
                result.add(item);
            }
        }
        return result.toArray(new IData[0]);
    }

    /** Null coalesce: return first non-null value */
    public static Object coalesce(Object a, Object b) {
        return a != null ? a : b;
    }

    // ---- List builder (for results[] = val pattern) ----

    /** Append a value to a list stored in the pipeline */
    public void appendToList(String key, Object value) {
        Object existing = get(key);
        List<Object> list;
        if (existing instanceof List) {
            list = (List<Object>) existing;
        } else {
            list = new ArrayList<>();
            if (existing != null) {
                list.add(existing);
            }
        }
        list.add(value);
        set(key, list);
    }

    /** Convert accumulated lists to arrays before returning */
    /** Convert accumulated lists to arrays before returning pipeline */
    public void finalizeLists() {
        IDataCursor c = pipeline.getCursor();
        try {
            c.first();
            while (c.hasMoreData()) {
                Object val = c.getValue();
                if (val instanceof List) {
                    List<?> list = (List<?>) val;
                    if (list.isEmpty()) {
                        c.setValue(new Object[0]);
                    } else {
                        // Check ALL elements to determine uniform type
                        boolean allIData = true, allString = true;
                        for (Object item : list) {
                            if (!(item instanceof IData)) allIData = false;
                            if (!(item instanceof String)) allString = false;
                        }
                        if (allIData) {
                            c.setValue(list.toArray(new IData[0]));
                        } else if (allString) {
                            c.setValue(list.toArray(new String[0]));
                        } else {
                            c.setValue(list.toArray());
                        }
                    }
                }
                c.next();
            }
        } finally {
            c.destroy();
        }
    }

    // ---- Logging helpers — uses pub.flow:debugLog via Service.doInvoke ----

    public static void logError(String msg) {
        doLog("ERROR", msg);
    }

    public static void logWarn(String msg) {
        doLog("WARN", msg);
    }

    public static void logInfo(String msg) {
        doLog("INFO", msg);
    }

    public static void logDebug(String msg) {
        doLog("DEBUG", msg);
    }

    private static void doLog(String level, String msg) {
        try {
            IData input = IDataFactory.create();
            IDataCursor c = input.getCursor();
            IDataUtil.put(c, "message", "[WmScript " + level + "] " + msg);
            c.destroy();
            Service.doInvoke(NSName.create("pub.flow:debugLog"), input);
        } catch (Exception e) {
            // fallback to stderr
            System.err.println("[WmScript " + level + "] " + msg);
        }
    }

    // ---- Comparison and boolean helpers (used by generated code via static import) ----

    /** Boolean coercion: null=false, Boolean as-is, "true"/"false" strings, non-null=true */
    public static boolean toBoolean(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).doubleValue() != 0;
        String s = val.toString();
        if ("false".equalsIgnoreCase(s) || s.isEmpty()) return false;
        return true;
    }

    /** Equality check: null-safe, uses .equals() */
    public static boolean eq(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.equals(b) || a.toString().equals(b.toString());
    }

    /** Compare two values: numeric if both parseable, otherwise string comparison */
    @SuppressWarnings("unchecked")
    public static int cmp(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        try {
            double da = toNum(a);
            double db = toNum(b);
            return Double.compare(da, db);
        } catch (NumberFormatException e) {
            return a.toString().compareTo(b.toString());
        }
    }

    /** Convert value to iterable Object[] for for-loops */
    public static Object[] toIterable(Object val) {
        if (val == null) return new Object[0];
        if (val instanceof Object[]) return (Object[]) val;
        if (val instanceof IData[]) return (IData[]) val;
        if (val instanceof Collection) return ((Collection<?>) val).toArray();
        if (val instanceof IData) {
            // Iterate over values of a document
            return values(val);
        }
        return new Object[] { val };
    }

    /** Add: if both numeric, numeric add; otherwise string concatenation */
    public static Object add(Object a, Object b) {
        if (a == null && b == null) return null;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() + ((Number) b).doubleValue();
        }
        try {
            double da = toNum(a);
            double db = toNum(b);
            return da + db;
        } catch (Exception e) {
            return toStr(a) + toStr(b);
        }
    }

    /** Array/list index access */
    public static Object arrayGet(Object array, Object index) {
        if (array == null) return null;
        int idx;
        if (index instanceof Number) {
            idx = ((Number) index).intValue();
        } else {
            idx = Integer.parseInt(index.toString());
        }
        if (array instanceof Object[]) {
            Object[] arr = (Object[]) array;
            if (idx < 0) idx = arr.length + idx;
            return (idx >= 0 && idx < arr.length) ? arr[idx] : null;
        }
        if (array instanceof List) {
            List<?> list = (List<?>) array;
            if (idx < 0) idx = list.size() + idx;
            return (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
        }
        return null;
    }

    // ---- Document creation ----

    public static IData createDocument(Object... keyValues) {
        IData doc = IDataFactory.create();
        IDataCursor c = doc.getCursor();
        try {
            for (int i = 0; i < keyValues.length; i += 2) {
                IDataUtil.put(c, (String) keyValues[i], keyValues[i + 1]);
            }
        } finally {
            c.destroy();
        }
        return doc;
    }
}
