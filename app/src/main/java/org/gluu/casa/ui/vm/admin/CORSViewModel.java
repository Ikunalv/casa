package org.gluu.casa.ui.vm.admin;

import org.gluu.casa.misc.Utils;
import org.gluu.casa.ui.UIUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.util.resource.Labels;

import java.util.List;

public class CORSViewModel extends MainViewModel {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private String origin;
    private List<String> origins;

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public List<String> getOrigins() {
        return origins;
    }

    @Init
    public void init() {
        //Obtain a reference to the list
        origins = getSettings().getCorsDomains();
    }

    @NotifyChange("origins")
    @Command
    public void dropOrigin(@BindingParam("origin") String orig) {
        origins.remove(orig);
        updateMainSettings();
    }

    @Command
    @NotifyChange({"origins", "origin"})
    public void addOrigin() {

        if (Utils.isValidUrl(origin)) {
            if (!origins.contains(origin.toLowerCase())) {
                origins.add(origin.toLowerCase());
                if (updateMainSettings()) {
                    origin = "";
                }
            }
        } else {
            UIUtils.showMessageUI(false, Labels.getLabel("amd.cors_invalid_origin", new String[]{ origin }));
        }

    }

}
