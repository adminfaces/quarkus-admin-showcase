package com.github.adminfaces.showcase;

import net.bull.javamelody.SessionListener;

import javax.servlet.*;
import javax.servlet.annotation.WebListener;
import java.util.EnumSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.adminfaces.template.util.Assert.has;

@WebListener
public class StartupListener implements ServletContextListener {


    @Override
    public void contextInitialized(ServletContextEvent sce) {
        sce.getServletContext().setInitParameter("primefaces.THEME", "admin");
        String viewsInSession = has(System.getenv("ViewsInSession")) ? System.getenv("ViewsInSession") : "3";
        sce.getServletContext().setInitParameter("com.sun.faces.numberOfLogicalViews",viewsInSession) ;
        sce.getServletContext().setInitParameter("com.sun.faces.numberOfViewsInSession", viewsInSession);
        sce.getServletContext().setInitParameter("org.omnifaces.VIEW_SCOPE_MANAGER_MAX_ACTIVE_VIEW_SCOPES", viewsInSession);
        try {
            sce.getServletContext().createListener(SessionListener.class);
        } catch (ServletException e) {
            Logger.getLogger(StartupListener.class.getName()).log(Level.SEVERE, "Could not create melody listener", e);
        }
        FilterRegistration.Dynamic gzipResponseFilter = sce.getServletContext().addFilter("gzipResponseFilter", "org.omnifaces.filter.GzipResponseFilter");
        gzipResponseFilter.setInitParameter("threshold", "200");
        gzipResponseFilter.addMappingForServletNames(EnumSet.of(DispatcherType.ERROR), true, "Faces Servlet");
        FilterRegistration.Dynamic javaMelodyFilter = sce.getServletContext().addFilter("javamelody", "net.bull.javamelody.MonitoringFilter");
        if(javaMelodyFilter != null) { //sometimes when running via java -jar it is null
            javaMelodyFilter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
            javaMelodyFilter.setAsyncSupported(true);
        }
        sce.getServletContext().setSessionTrackingModes(EnumSet.of(SessionTrackingMode.COOKIE));
    }


}
