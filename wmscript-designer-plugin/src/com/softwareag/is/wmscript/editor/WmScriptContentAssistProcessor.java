package com.softwareag.is.wmscript.editor;

import com.softwareag.is.core.iscomm.server.IServerConnection;
import com.wm.data.*;
import com.wm.lang.ns.NSName;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.*;

import java.util.*;

/**
 * Content assist processor for WmScript in Designer.
 * Provides autocompletion for:
 * - Service names after "invoke"
 * - Service input parameters inside invoke arguments
 * - Output fields on left side of =
 * - Input fields + local vars + builtins in expressions
 */
public class WmScriptContentAssistProcessor implements IContentAssistProcessor {

    private final IServerConnection server;
    private final String currentServicePath;

    // Cached data
    private List<String> allServices;
    private Map<String, List<String[]>> signatureCache = new HashMap<>();
    private List<String[]> currentInputFields;
    private List<String[]> currentOutputFields;

    // Builtins
    private static final String[] BUILTINS = {
        "num", "str", "int", "date", "len", "sum", "min", "max",
        "join", "split", "keys", "values", "exists", "typeof"
    };
    private static final String[] KEYWORDS = {
        "invoke", "if", "elif", "else", "end", "for", "in", "while",
        "try", "catch", "raise", "return", "break", "continue", "skip",
        "true", "false", "null", "and", "or", "log"
    };

    enum Context { INVOKE_SERVICE, INVOKE_ARGS, ASSIGNMENT_LEFT, EXPRESSION }

    public WmScriptContentAssistProcessor(IServerConnection server, String currentServicePath) {
        this.server = server;
        this.currentServicePath = currentServicePath;
        loadCurrentSignature();
    }

    @Override
    public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset) {
        try {
            IDocument doc = viewer.getDocument();
            String text = doc.get(0, offset);

            // Find the prefix being typed
            String prefix = getPrefix(text);
            int prefixStart = offset - prefix.length();

            // Detect context
            Context ctx = detectContext(text);
            List<ICompletionProposal> proposals = new ArrayList<>();

            // Always add builtins and keywords as fallback
            for (String k : KEYWORDS) {
                if (prefix.isEmpty() || k.startsWith(prefix.toLowerCase())) {
                    proposals.add(new CompletionProposal(
                        k, prefixStart, prefix.length(),
                        k.length(), null, k + " [keyword]", null, null));
                }
            }
            for (String b : BUILTINS) {
                if (prefix.isEmpty() || b.startsWith(prefix.toLowerCase())) {
                    String insert = b + "(";
                    proposals.add(new CompletionProposal(
                        insert, prefixStart, prefix.length(),
                        insert.length(), null, b + "(...) [builtin]", null, null));
                }
            }

            switch (ctx) {
                case INVOKE_SERVICE:
                    for (String svc : getAllServices()) {
                        if (svc.toLowerCase().contains(prefix.toLowerCase())
                                && !svc.startsWith("DEBUG") && !svc.startsWith("ERR")
                                && !svc.startsWith("NODE") && !svc.startsWith("NODELIST")
                                && !svc.startsWith("(no ")) {
                            proposals.add(new WmScriptServiceProposal(
                                svc, prefixStart, prefix.length(), server));
                        }
                    }
                    break;

                case INVOKE_ARGS:
                    String targetService = extractInvokeTarget(text);
                    if (targetService != null) {
                        for (String[] field : getServiceInputFields(targetService)) {
                            String name = field[0];
                            String type = field[1];
                            if (name.toLowerCase().startsWith(prefix.toLowerCase())) {
                                String insert = name + ": ";
                                proposals.add(new CompletionProposal(
                                    insert, prefixStart, prefix.length(),
                                    insert.length(), null,
                                    name + " : " + type, null, null));
                            }
                        }
                    }
                    break;

                case ASSIGNMENT_LEFT:
                    if (currentOutputFields != null) {
                        for (String[] field : currentOutputFields) {
                            String name = field[0];
                            String type = field[1];
                            if (name.toLowerCase().startsWith(prefix.toLowerCase())) {
                                proposals.add(new CompletionProposal(
                                    name, prefixStart, prefix.length(),
                                    name.length(), null,
                                    name + " : " + type + " [output]", null, null));
                            }
                        }
                    }
                    break;

                case EXPRESSION:
                    // Input fields
                    if (currentInputFields != null) {
                        for (String[] field : currentInputFields) {
                            String name = field[0];
                            String type = field[1];
                            if (name.toLowerCase().startsWith(prefix.toLowerCase())) {
                                proposals.add(new CompletionProposal(
                                    name, prefixStart, prefix.length(),
                                    name.length(), null,
                                    name + " : " + type + " [input]", null, null));
                            }
                        }
                    }
                    // builtins and keywords already added above
                    break;
            }

            return proposals.toArray(new ICompletionProposal[0]);
        } catch (Exception e) {
            return new ICompletionProposal[0];
        }
    }

    @Override
    public IContextInformation[] computeContextInformation(ITextViewer viewer, int offset) {
        return null;
    }

    @Override
    public char[] getCompletionProposalAutoActivationCharacters() {
        return new char[] { '.', ':' };
    }

    @Override
    public char[] getContextInformationAutoActivationCharacters() {
        return null;
    }

    @Override
    public String getErrorMessage() {
        return null;
    }

    @Override
    public IContextInformationValidator getContextInformationValidator() {
        return null;
    }

    // ================================================================
    // Context detection
    // ================================================================

    private Context detectContext(String textBeforeCursor) {
        String trimmed = textBeforeCursor.trim();

        // Check if we're after "invoke" keyword
        if (trimmed.endsWith("invoke") || trimmed.matches("(?s).*invoke\\s+[a-zA-Z0-9_.]*$")) {
            return Context.INVOKE_SERVICE;
        }

        // Check if we're inside invoke arguments (after "(" or ",")
        int lastOpenParen = textBeforeCursor.lastIndexOf('(');
        int lastCloseParen = textBeforeCursor.lastIndexOf(')');
        if (lastOpenParen > lastCloseParen) {
            // We're inside parens — check if there's an invoke before them
            String beforeParen = textBeforeCursor.substring(0, lastOpenParen).trim();
            if (beforeParen.matches("(?s).*invoke\\s+[a-zA-Z0-9_.]+:[a-zA-Z0-9_]+$")) {
                return Context.INVOKE_ARGS;
            }
        }

        // Check if we're on the left side of = (beginning of line)
        String lastLine = getLastLine(textBeforeCursor);
        if (!lastLine.contains("=") && !lastLine.trim().startsWith("invoke")
                && !lastLine.trim().startsWith("if") && !lastLine.trim().startsWith("for")
                && !lastLine.trim().startsWith("while") && !lastLine.trim().startsWith("log")) {
            return Context.ASSIGNMENT_LEFT;
        }

        return Context.EXPRESSION;
    }

    private String extractInvokeTarget(String textBeforeCursor) {
        int lastOpenParen = textBeforeCursor.lastIndexOf('(');
        if (lastOpenParen < 0) return null;
        String before = textBeforeCursor.substring(0, lastOpenParen).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("invoke\\s+([a-zA-Z0-9_.]+:[a-zA-Z0-9_]+)$")
            .matcher(before);
        return m.find() ? m.group(1) : null;
    }

    private String getPrefix(String text) {
        int i = text.length() - 1;
        while (i >= 0 && (Character.isLetterOrDigit(text.charAt(i))
                || text.charAt(i) == '_' || text.charAt(i) == '.' || text.charAt(i) == ':')) {
            i--;
        }
        return text.substring(i + 1);
    }

    private String getLastLine(String text) {
        int lastNewline = text.lastIndexOf('\n');
        return lastNewline >= 0 ? text.substring(lastNewline + 1) : text;
    }

    // ================================================================
    // IS API calls (with caching)
    // ================================================================

    private void loadCurrentSignature() {
        currentInputFields = new ArrayList<>();
        currentOutputFields = new ArrayList<>();
        loadSignatureFields(currentServicePath, currentInputFields, currentOutputFields);
    }

    private void loadSignatureFields(String servicePath, List<String[]> inputTarget, List<String[]> outputTarget) {
        try {
            IData input = IDataFactory.create();
            IDataCursor c = input.getCursor();
            IDataUtil.put(c, "name", servicePath);
            c.destroy();

            IData result = server.invoke(NSName.create("wm.server.ns:getNode"), input);
            IDataCursor rc = result.getCursor();

            // Navigate: result → node → svc_sig → sig_in/sig_out
            // Use get() instead of getIData() to handle Values objects
            Object nodeObj = IDataUtil.get(rc, "node");
            rc.destroy();
            if (nodeObj instanceof IData) {
                IDataCursor nc = ((IData) nodeObj).getCursor();
                Object sigObj = IDataUtil.get(nc, "svc_sig");
                nc.destroy();
                if (sigObj instanceof IData) {
                    IDataCursor sc = ((IData) sigObj).getCursor();
                    Object sigIn = IDataUtil.get(sc, "sig_in");
                    Object sigOut = IDataUtil.get(sc, "sig_out");
                    sc.destroy();
                    if (inputTarget != null && sigIn instanceof IData) extractFields((IData) sigIn, inputTarget);
                    if (outputTarget != null && sigOut instanceof IData) extractFields((IData) sigOut, outputTarget);
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void extractFields(IData sigRecord, List<String[]> target) {
        if (sigRecord == null) return;
        try {
            IDataCursor c = sigRecord.getCursor();
            IData[] fields = IDataUtil.getIDataArray(c, "rec_fields");
            c.destroy();
            if (fields != null) {
                for (IData field : fields) {
                    IDataCursor fc = field.getCursor();
                    String name = IDataUtil.getString(fc, "field_name");
                    String type = IDataUtil.getString(fc, "field_type");
                    fc.destroy();
                    if (name != null) {
                        target.add(new String[] { name, type != null ? type : "string" });
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private List<String> getAllServices() {
        if (allServices != null) return allServices;
        allServices = new ArrayList<>();
        try {
            // Get all packages
            IData pkgResult = server.invoke(
                NSName.create("wm.server.packages:packageList"), IDataFactory.create());
            IDataCursor pc = pkgResult.getCursor();
            IData[] packages = IDataUtil.getIDataArray(pc, "packages");
            pc.destroy();

            if (packages != null) {
                for (IData pkg : packages) {
                    IDataCursor pkc = pkg.getCursor();
                    String pkgName = IDataUtil.getString(pkc, "name");
                    String enabled = IDataUtil.getString(pkc, "enabled");
                    pkc.destroy();
                    if (pkgName == null) continue;
                    if (enabled != null && !"true".equals(enabled)) continue;
                    loadServicesFromPackage(pkgName, "");
                }
            }
        } catch (Exception e) {
            allServices.add("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (allServices.isEmpty()) {
            // Debug: dump what the API actually returns
            try {
                IData pkgResult = server.invoke(
                    NSName.create("wm.server.packages:packageList"), IDataFactory.create());
                IDataCursor dc = pkgResult.getCursor();
                StringBuilder keys = new StringBuilder("keys=[");
                while (dc.next()) {
                    keys.append(dc.getKey()).append("(").append(
                        dc.getValue() != null ? dc.getValue().getClass().getSimpleName() : "null"
                    ).append("), ");
                }
                dc.destroy();
                keys.append("]");
                allServices.add("DEBUG: " + keys.toString());
            } catch (Exception e2) {
                allServices.add("DEBUG_ERR: " + e2.getMessage());
            }
        }
        return allServices;
    }

    private void loadServicesFromPackage(String pkgName, String folder) {
        try {
            IData input = IDataFactory.create();
            IDataCursor c = input.getCursor();
            IDataUtil.put(c, "package", pkgName);
            if (!folder.isEmpty()) IDataUtil.put(c, "interface", folder);
            c.destroy();

            IData result = server.invoke(NSName.create("wm.server.ns:getNodeList"), input);
            IDataCursor rc = result.getCursor();
            IData[] nodes = IDataUtil.getIDataArray(rc, "nodeList");
            rc.destroy();

            if (nodes != null) {
                for (IData node : nodes) {
                    IDataCursor nc = node.getCursor();
                    String nsName = IDataUtil.getString(nc, "node_nsName");
                    Object nodeTypeObj = IDataUtil.get(nc, "node_type");
                    nc.destroy();

                    if (nodeTypeObj != null) {
                        String typeName = nodeTypeObj.toString(); // NSType.toString() returns "interface", "service", etc.
                        if (typeName.contains("service") || nodeTypeObj instanceof com.wm.lang.ns.NSServiceType) {
                            allServices.add(nsName);
                        } else if ("interface".equals(typeName)) {
                            loadServicesFromPackage(pkgName, nsName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            allServices.add("ERR(" + pkgName + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private List<String[]> getServiceInputFields(String servicePath) {
        if (signatureCache.containsKey(servicePath)) {
            return signatureCache.get(servicePath);
        }
        List<String[]> fields = new ArrayList<>();
        loadSignatureFields(servicePath, fields, null);
        signatureCache.put(servicePath, fields);
        return fields;
    }
}
