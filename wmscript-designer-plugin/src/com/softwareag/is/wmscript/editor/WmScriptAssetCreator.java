package com.softwareag.is.wmscript.editor;

import com.softwareag.is.ui.navigator.model.ISAssetCreator;
import com.softwareag.is.ui.navigator.model.ISLeafNode;
import com.softwareag.is.core.iscomm.server.IServerConnection;
import com.wm.app.b2b.client.ns.NSNodeStub;
import com.wm.lang.ns.NSNode;
import com.wm.lang.ns.NSService;
import com.wm.lang.ns.NSServiceType;
import com.wm.lang.ns.NSType;
import org.eclipse.jface.resource.ImageDescriptor;

/**
 * Asset creator for WmScript services — same pattern as CSCServiceAssetCreator.
 */
public class WmScriptAssetCreator extends ISAssetCreator {

    @Override
    protected ISLeafNode create(Object isData, IServerConnection server) {
        NSNodeStub nsNode = null;
        NSServiceType nodeType = null;
        String nodeName = "";

        if (isData instanceof NSService) {
            NSService nsService = (NSService) isData;
            nsNode = (NSNodeStub) isData;
            nodeType = nsService.getServiceType();
            nodeName = nsService.getNSName().getNodeName().toString();
        } else if (isData instanceof NSNodeStub) {
            nsNode = (NSNodeStub) isData;
            nodeType = nsNode.getServiceType();
            nodeName = nsNode.getNSName().getNodeName().toString();
        }

        if (nodeType != null && "wmscript".equals(nodeType.getType())) {
            return new ISWmScriptServiceNode(nodeName, (NSNode) nsNode, server);
        }

        return null;
    }

    @Override
    public ImageDescriptor getImageDescriptor(NSType type) {
        return null;
    }
}
