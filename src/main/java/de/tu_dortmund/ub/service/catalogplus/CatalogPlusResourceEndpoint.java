/*
The MIT License (MIT)

Copyright (c) 2015, Hans-Georg Becker, http://orcid.org/0000-0003-0432-294X

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package de.tu_dortmund.ub.service.catalogplus;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.tu_dortmund.ub.service.catalogplus.model.RequestError;
import de.tu_dortmund.ub.util.impl.Lookup;
import de.tu_dortmund.ub.util.impl.Mailer;
import de.tu_dortmund.ub.util.output.ObjectToHtmlTransformation;
import de.tu_dortmund.ub.util.output.TransformationException;
import de.tu_dortmund.ub.util.rights.AnalyseIPRange;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import javax.mail.MessagingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.*;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Created by cihabe on 16.05.2015.
 */
public class CatalogPlusResourceEndpoint extends HttpServlet {

    // Configuration
    private Properties config = new Properties();
    private Logger logger = Logger.getLogger(CatalogPlusResourceEndpoint.class.getName());

    private String format;
    private String language;
    private boolean isTUintern;
    private boolean isUBintern;

    public CatalogPlusResourceEndpoint() throws IOException {

        this("conf/config.properties");
    }

    public CatalogPlusResourceEndpoint(String conffile) throws IOException {

        // Init properties
        try {
            InputStream inputStream = new FileInputStream(conffile);

            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));

                try {
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

        this.logger.info("[" + this.config.getProperty("service.name") + "] " + "Starting 'CatalogPlusResourceEndpoint' ...");
        this.logger.info("[" + this.config.getProperty("service.name") + "] " + "conf-file = " + conffile);
        this.logger.info("[" + this.config.getProperty("service.name") + "] " + "log4j-conf-file = " + this.config.getProperty("service.log4j-conf"));
    }

    public void doOptions(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {

        httpServletResponse.setHeader("Access-Control-Allow-Methods", config.getProperty("Access-Control-Allow-Methods"));
        httpServletResponse.addHeader("Access-Control-Allow-Headers", config.getProperty("Access-Control-Allow-Headers"));
        httpServletResponse.setHeader("Accept", config.getProperty("Accept"));
        httpServletResponse.setHeader("Access-Control-Allow-Origin", config.getProperty("Access-Control-Allow-Origin"));

        httpServletResponse.getWriter().println();
    }

    public void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException {

        httpServletResponse.setHeader("Access-Control-Allow-Origin", "*");

        this.logger.debug("PathInfo = " + httpServletRequest.getPathInfo());
        this.logger.debug("QueryString = " + httpServletRequest.getQueryString());

        // analyse request path to define the service
        String path = httpServletRequest.getPathInfo();

        String service = "";

        if (path != null) {

            String[] params = path.substring(1, path.length()).split("/");

            if (params.length == 1) {
                service = params[0];
            } else if (path.startsWith("katalog/titel/")) {
                service = "api";
            }
        }

        this.logger.debug("service = " + service);

        // analyse ip range
        String ips = httpServletRequest.getHeader("X-Forwarded-For");

        this.isTUintern = AnalyseIPRange.analyseAccessRights(ips, config.getProperty("service.iprange.tu"), config.getProperty("service.iprange.tu.exceptions"));
        this.isUBintern = AnalyseIPRange.analyseAccessRights(ips, config.getProperty("service.iprange.ub"), config.getProperty("service.iprange.ub.exceptions"));

        this.logger.debug("[" + this.config.getProperty("service.name") + "] " + "Where is it from? " + httpServletRequest.getHeader("X-Forwarded-For") + ", " + isTUintern + ", " + isUBintern);

        // format
        this.format = "html";

        if (httpServletRequest.getParameter("format") != null && !httpServletRequest.getParameter("format").equals("")) {

            this.format = httpServletRequest.getParameter("format");
        } else {

            Enumeration<String> headerNames = httpServletRequest.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerNameKey = headerNames.nextElement();

                if (headerNameKey.equals("Accept")) {

                    this.logger.debug("headerNameKey = " + httpServletRequest.getHeader(headerNameKey));

                    if (httpServletRequest.getHeader(headerNameKey).contains("text/html")) {
                        this.format = "html";
                    } else if (httpServletRequest.getHeader(headerNameKey).contains("application/xml")) {
                        this.format = "xml";
                    } else if (httpServletRequest.getHeader(headerNameKey).contains("application/json")) {
                        this.format = "json";
                    }
                }
                if (headerNameKey.equals("Accept-Language")) {
                    this.language = httpServletRequest.getHeader(headerNameKey);
                    this.logger.debug("[" + config.getProperty("service.name") + "] " + "Accept-Language: " + this.language);
                }
            }
        }

        this.logger.info("format = " + this.format);

        // language
        if (this.language != null && this.language.startsWith("de")) {
            this.language = "de";
        } else if (this.language != null && this.language.startsWith("en")) {
            this.language = "en";
        } else if (httpServletRequest.getParameter("l") != null) {
            this.language = httpServletRequest.getParameter("l");
        } else {
            this.language = "de";
        }

        this.logger.info("language = " + this.language);

        // Debugging
        boolean debug = false;

        if (httpServletRequest.getParameter("debug") != null && httpServletRequest.getParameter("debug").equals("1")) {
            debug = true;
        }


        if (!service.equals("records") && !service.equals("classes")) {

            RequestError requestError = new RequestError();
            requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
            requestError.setDescription("The service '" + path + "' is not implemented.");
            requestError.setError("BAD REQUEST");

            httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            this.sendRequestError(httpServletResponse, requestError);
        }
        else {

            try {

                // TODO handle resource requests
            }
            catch (Exception e) {

                this.logger.error("[" + this.config.getProperty("service.name") + "] Exception: " + HttpServletResponse.SC_SERVICE_UNAVAILABLE + " Service unavailable.");
                this.logger.error(e.getMessage(), e.getCause());
                String stackTrace = "";
                for (StackTraceElement stackTraceElement : e.getStackTrace()) {
                    this.logger.error("\t" + stackTraceElement.toString());
                    stackTrace += stackTraceElement.toString() + "\n";
                }

                httpServletResponse.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service unavailable.");
                RequestError requestError = new RequestError();
                requestError.setCode(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                requestError.setDescription("Unexpected error in request: '" + httpServletRequest.getRequestURL() + "'!\n" + stackTrace);
                requestError.setError("SERVICE_UNAVAILABLE");

                httpServletResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                this.sendRequestError(httpServletResponse, requestError);
            }
        }
    }

    private void sendRequestError(HttpServletResponse httpServletResponse, RequestError requestError) {

        if (requestError.getCode() == HttpServletResponse.SC_SERVICE_UNAVAILABLE || requestError.getCode() == HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {

            try {

                Mailer mailer = new Mailer(this.config.getProperty("service.mailer.conf"));
                mailer.postMail("[" + this.config.getProperty("service.name") + "] Exception: " + requestError.getCode() + " Service unavailable.", requestError.getDescription());

            } catch (MessagingException | IOException e1) {

                this.logger.error(e1.getMessage(), e1.getCause());
            }
        }

        ObjectMapper mapper = new ObjectMapper();

        httpServletResponse.setHeader("WWW-Authentificate", "Bearer");
        httpServletResponse.setHeader("WWW-Authentificate", "Bearer realm=\"PAIA auth\"");
        httpServletResponse.setContentType("application/json");

        try {

            if (this.format.equals("html")) {

                if (Lookup.lookupAll(ObjectToHtmlTransformation.class).size() > 0) {

                    try {
                        ObjectToHtmlTransformation htmlTransformation = Lookup.lookup(ObjectToHtmlTransformation.class);
                        // init transformator
                        htmlTransformation.init(this.config);

                        Properties parameters = new Properties();
                        parameters.setProperty("lang", this.language);
                        parameters.setProperty("isTUintern", Boolean.toString(isTUintern));
                        parameters.setProperty("isUBintern", Boolean.toString(isUBintern));

                        httpServletResponse.setContentType("text/html;charset=UTF-8");
                        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                        httpServletResponse.getWriter().println(htmlTransformation.transform(requestError, parameters));
                    }
                    catch (TransformationException e) {
                        httpServletResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error: Error while rendering a HTML message.");
                    }
                }
                else {
                    this.logger.error("ObjectToHtmlTransformation not configured! Switch to JSON.");
                    this.format = "json";
                }
            }

            // XML-Ausgabe mit JAXB
            if (this.format.equals("xml")) {

                try {

                    JAXBContext context = JAXBContext.newInstance(RequestError.class);
                    Marshaller m = context.createMarshaller();
                    m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);

                    // Write to HttpResponse
                    httpServletResponse.setContentType("application/xml;charset=UTF-8");
                    m.marshal(requestError, httpServletResponse.getWriter());
                } catch (JAXBException e) {
                    this.logger.error(e.getMessage(), e.getCause());
                    httpServletResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error: Error while rendering the results.");
                }
            }

            // JSON-Ausgabe mit Jackson
            if (this.format.equals("json")) {

                httpServletResponse.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(httpServletResponse.getWriter(), requestError);
            }
        }
        catch (Exception e) {

            e.printStackTrace();
        }
    }
}
