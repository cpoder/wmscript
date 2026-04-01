package com.softwareag.is.wmscript.editor;

import com.softwareag.is.core.iscomm.server.IServerConnection;
import com.wm.data.*;
import com.wm.lang.ns.NSName;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Completion proposal for IS service names.
 * On apply, fetches the service's input signature and inserts
 * the full invoke template: serviceName(param1: , param2: )
 */
public class WmScriptServiceProposal implements ICompletionProposal {

    private final String serviceName;
    private final int replacementOffset;
    private final int replacementLength;
    private final IServerConnection server;
    private int cursorPosition;

    public WmScriptServiceProposal(String serviceName, int offset, int length,
                                    IServerConnection server) {
        this.serviceName = serviceName;
        this.replacementOffset = offset;
        this.replacementLength = length;
        this.server = server;
        this.cursorPosition = serviceName.length();
    }

    @Override
    public void apply(IDocument document) {
        try {
            // Fetch the service's input signature
            List<String> inputParams = fetchInputParams();

            // Build the replacement text
            StringBuilder sb = new StringBuilder(serviceName);
            sb.append("(");
            for (int i = 0; i < inputParams.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(inputParams.get(i)).append(": ");
            }
            sb.append(")");

            String replacement = sb.toString();
            document.replace(replacementOffset, replacementLength, replacement);

            // Position cursor after first ": " (where user types the first value)
            if (!inputParams.isEmpty()) {
                cursorPosition = serviceName.length() + 1 + inputParams.get(0).length() + 2;
            } else {
                cursorPosition = replacement.length();
            }
        } catch (Exception e) {
            // Fallback: just insert the service name
            try {
                document.replace(replacementOffset, replacementLength, serviceName);
                cursorPosition = serviceName.length();
            } catch (Exception e2) {
                // ignore
            }
        }
    }

    private List<String> fetchInputParams() {
        List<String> params = new ArrayList<>();
        try {
            IData input = IDataFactory.create();
            IDataCursor c = input.getCursor();
            IDataUtil.put(c, "name", serviceName);
            c.destroy();

            IData result = server.invoke(NSName.create("wm.server.ns:getNode"), input);
            IDataCursor rc = result.getCursor();
            IData node = IDataUtil.getIData(rc, "node");
            rc.destroy();

            if (node != null) {
                IDataCursor nc = node.getCursor();
                IData sig = IDataUtil.getIData(nc, "svc_sig");
                nc.destroy();

                if (sig != null) {
                    IDataCursor sc = sig.getCursor();
                    IData sigIn = IDataUtil.getIData(sc, "sig_in");
                    sc.destroy();

                    if (sigIn != null) {
                        IDataCursor ic = sigIn.getCursor();
                        IData[] fields = IDataUtil.getIDataArray(ic, "rec_fields");
                        ic.destroy();

                        if (fields != null) {
                            for (IData field : fields) {
                                IDataCursor fc = field.getCursor();
                                String name = IDataUtil.getString(fc, "field_name");
                                fc.destroy();
                                if (name != null) {
                                    params.add(name);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore — return empty params
        }
        return params;
    }

    @Override
    public Point getSelection(IDocument document) {
        return new Point(replacementOffset + cursorPosition, 0);
    }

    @Override
    public String getDisplayString() {
        return serviceName;
    }

    @Override
    public String getAdditionalProposalInfo() {
        return null;
    }

    @Override
    public Image getImage() {
        return null;
    }

    @Override
    public IContextInformation getContextInformation() {
        return null;
    }
}
