package de.tu_dortmund.ub.service.catalogplus;

import de.tu_dortmund.ub.service.catalogplus.rds.ResourceDiscoveryService;
import de.tu_dortmund.ub.service.catalogplus.vcs.VirtualClassificationSystem;
import de.tu_dortmund.ub.util.impl.Lookup;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Properties;

/**
 * Created by Hans-Georg on 24.07.2015.
 */
public class HealthEndpoint extends HttpServlet {

    private Properties config = null;
    private Logger logger = null;

    public HealthEndpoint() throws IOException {

        this("conf/api-test.properties");
    }

    public HealthEndpoint(String conffile) throws IOException {

        // Init properties
        try {
            InputStream inputStream = new FileInputStream(conffile);

            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));

                try {

                    this.config = new Properties();
                    this.config.load(reader);

                } finally {
                    reader.close();
                }
            }
            finally {
                inputStream.close();
            }
        }
        catch (IOException e) {
            System.out.println("FATAL ERROR: Die Datei '" + conffile + "' konnte nicht geöffnet werden!");
        }

        // init logger
        PropertyConfigurator.configure(this.config.getProperty("service.log4j-conf"));
        this.logger = Logger.getLogger(HealthEndpoint.class.getName());

        this.logger.info("Starting 'HealthEndpoint' ...");
        this.logger.info("conf-file = " + conffile);
        this.logger.info("log4j-conf-file = " + this.config.getProperty("service.log4j-conf"));
    }

    public void doOptions(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        response.setHeader("Access-Control-Allow-Methods", config.getProperty("Access-Control-Allow-Methods"));
        response.addHeader("Access-Control-Allow-Headers", config.getProperty("Access-Control-Allow-Headers"));
        response.setHeader("Accept", config.getProperty("Accept"));
        response.setHeader("Access-Control-Allow-Origin", config.getProperty("Access-Control-Allow-Origin"));
        response.setHeader("Cache-Control", config.getProperty("Cache-Control"));

        response.getWriter().println();
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        response.setHeader("Access-Control-Allow-Origin", config.getProperty("Access-Control-Allow-Origin"));
        response.setHeader("Cache-Control", config.getProperty("Cache-Control"));

        try {

            HashMap<String,String> health = null;

            // Resource Discovery Service
            if (Lookup.lookupAll(ResourceDiscoveryService.class).size() > 0) {

                ResourceDiscoveryService resourceDiscoveryService = Lookup.lookup(ResourceDiscoveryService.class);
                resourceDiscoveryService.init(this.config, "");

                health = resourceDiscoveryService.health(this.config);
            }
            // Virtuelle Systematik
            /*
            if (Lookup.lookupAll(VirtualClassificationSystem.class).size() > 0) {

                VirtualClassificationSystem virtualClassificationSystem = Lookup.lookup(VirtualClassificationSystem.class);
                virtualClassificationSystem.init(this.config);

                if (health == null) {

                    health = virtualClassificationSystem.health(this.config);
                }
                else {

                    health.putAll(virtualClassificationSystem.health(this.config));
                }
            }
            */

            String json = "{ ";

            json += "\"name\" : \"" + config.getProperty("service.name") + "\",";
            json += "\"timestamp\" : \"" + LocalDateTime.now() + "\"";

            if (health != null && health.size() > 0) {

                json += ", \"dependencies\" : { ";

                for (String dependency : health.keySet()) {

                    json += "\"" + dependency + "\" : \"" + health.get(dependency) + "\", ";
                }

                json = json.substring(0, json.length() - 2);

                json += "}";
            }

            json += " }";

            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println(json);
        }
        catch (Exception e) {

            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().println("Could not check system health!");
        }
    }
}
