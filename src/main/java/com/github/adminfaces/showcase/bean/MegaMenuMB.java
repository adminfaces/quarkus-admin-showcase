package com.github.adminfaces.showcase.bean;

import org.omnifaces.cdi.ViewScoped;

import javax.inject.Named;
import java.io.Serializable;

@Named
@ViewScoped
public class MegaMenuMB implements Serializable {

    private String orientation = "horizontal";
 
    public String getOrientation() {
        return orientation;
    }
 
    public void setOrientation(String orientation) {
        this.orientation = orientation;
    }

}
