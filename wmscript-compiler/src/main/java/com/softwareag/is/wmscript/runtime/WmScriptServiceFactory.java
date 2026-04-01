package com.softwareag.is.wmscript.runtime;

import com.wm.app.b2b.server.Package;
import com.wm.app.b2b.server.ns.NSPluginNodeFactory;
import com.wm.lang.ns.NSName;
import com.wm.lang.ns.NSNode;
import com.wm.util.Values;

import java.util.Locale;

/**
 * Factory that registers the "wmscript" service type with IS.
 * Discovered via nsplugins.cnf in the WmScript package.
 */
public class WmScriptServiceFactory implements NSPluginNodeFactory {

    @Override
    public String getNSType() {
        return "wmscript";
    }

    @Override
    public String getDisplayName(Locale locale) {
        return "WmScript Service";
    }

    @Override
    public String getDisplayInfo(Locale locale) {
        return "Integration services written in WmScript, compiled to Java at design time";
    }

    @Override
    public NSNode createFromNodeDef(Package pkg, NSName name, Values nodeDef) {
        if (nodeDef == null) {
            return null;
        }
        try {
            return new WmScriptService(pkg, name, nodeDef);
        } catch (Exception e) {
            com.wm.app.b2b.server.Server.logError(e);
            return null;
        }
    }
}
