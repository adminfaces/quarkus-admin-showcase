package com.github.adminfaces.showcase.bean;

import org.apache.commons.io.IOUtils;

import javax.enterprise.context.ApplicationScoped;
import javax.faces.context.FacesContext;
import javax.inject.Named;
import javax.servlet.ServletContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static com.github.adminfaces.template.util.Assert.has;

/**
 * Created by rmpestano on 21/01/17.
 * Bean used to add source code into pages
 */
@ApplicationScoped
@Named
public class SourceCodeMB {

    private Map<String, String> sourceMap = new HashMap<>();


    public String getJavaSource(String path) {
        path = path.replaceAll("\\.","/");
        if (sourceMap.containsKey(path)) {
            return sourceMap.get(path);
        } else {
            String source = loadSource(path,true);
            if (has(source)) {
                sourceMap.put(path, source);
            }
            return source;
        }
    }

    public String getPageSource(String path) {
        if (sourceMap.containsKey(path)) {
            return sourceMap.get(path);
        } else {
            String source = loadSource(path,false);
            if (has(source)) {
                sourceMap.put(path, source);
            }
            return source;
        }
    }

    public String getPageSource() {
        String viewId = FacesContext.getCurrentInstance().getViewRoot().getViewId();
        return getPageSource(viewId);
    }

    private String loadSource(String sourcePath, boolean isJavaSource) {
        String basePath = isJavaSource ? "/sources/" : "/";
        String sulfix = isJavaSource ? ".java":".xhtml";
        if(!sourcePath.endsWith(sulfix)){
            sourcePath = sourcePath +sulfix;
        }
        try (InputStream is = ((ServletContext) FacesContext.getCurrentInstance().getExternalContext().getContext()).getResourceAsStream(basePath + sourcePath)){
            return new String(IOUtils.toByteArray(is));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

}
