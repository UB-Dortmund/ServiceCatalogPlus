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
import de.tu_dortmund.ub.service.catalogplus.rds.RDSException;
import de.tu_dortmund.ub.service.catalogplus.rds.ResourceDiscoveryService;
import de.tu_dortmund.ub.service.catalogplus.vcs.VirtualClassificationSystem;
import de.tu_dortmund.ub.util.impl.Lookup;
import de.tu_dortmund.ub.util.impl.Mailer;
import de.tu_dortmund.ub.util.output.ObjectToHtmlTransformation;
import de.tu_dortmund.ub.util.output.TransformationException;
import de.tu_dortmund.ub.util.rights.AnalyseIPRange;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import javax.mail.MessagingException;
import javax.servlet.Servlet;
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
public class CatalogPlusServiceEndpoint extends HttpServlet {

    // Configuration
    private Properties config = new Properties();
    private Logger logger = Logger.getLogger(CatalogPlusServiceEndpoint.class.getName());

    private String format;
    private String language;
    private boolean isTUintern;
    private boolean isUBintern;

    public CatalogPlusServiceEndpoint() throws IOException {

        this("conf/config.properties");
    }

    public CatalogPlusServiceEndpoint(String conffile) throws IOException {

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

        this.logger.info("[" + this.config.getProperty("service.name") + "] " + "Starting 'CatalogPlusServiceEndpoint' ...");
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
            service = params[0];
        }

        this.logger.debug("service = " + service);

        // analyse ip range
        String ips = httpServletRequest.getHeader("X-Forwarded-For");

        this.isTUintern = AnalyseIPRange.analyseAccessRights(ips, config.getProperty("service.iprange.tu"), config.getProperty("service.iprange.tu.exceptions"));
        this.isUBintern = true; // TODO AnalyseIPRange.analyseAccessRights(ips, config.getProperty("service.iprange.ub"), config.getProperty("service.iprange.ub.exceptions"));

        this.logger.debug("[" + this.config.getProperty("service.name") + "] " + "Where is it from? " + httpServletRequest.getHeader("X-Forwarded-For") + ", " + isTUintern + ", " + isUBintern);

        // format
        this.format = "html";

        if (httpServletRequest.getParameter("format") != null && !httpServletRequest.getParameter("format").equals("")) {

            this.format = httpServletRequest.getParameter("format");
        }
        else {

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

        // handle service requests
        if (!service.equals("search") && !service.equals("class") && !service.equals("typeahead")) {

            RequestError requestError = new RequestError();
            requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
            requestError.setDescription("The service '" + path + "' is not implemented.");
            requestError.setError("BAD REQUEST");

            httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            this.sendRequestError(httpServletResponse, requestError);
        }
        else if (!service.equals("typeahead") && !this.format.equals("html") && !isUBintern) {

            RequestError requestError = new RequestError();
            requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
            requestError.setDescription("You are not allowed to request results in '" + this.format + "'!");
            requestError.setError("BAD REQUEST");

            httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            this.sendRequestError(httpServletResponse, requestError);
        }
        else if (service.equals("typeahead") && !this.format.equals("json")) {

            RequestError requestError = new RequestError();
            requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
            requestError.setDescription("Service 'typeahead' does not support format '" + this.format + "'!");
            requestError.setError("BAD REQUEST");

            httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            this.sendRequestError(httpServletResponse, requestError);
        }
        else {

            try {

                // Query
                Properties requestParameter = null;

                if (service.equals("search") || service.equals("typeahead")) {

                    requestParameter = this.handleRequestParameters(httpServletRequest);
                    requestParameter.setProperty("lang", this.language);

                }
                else if (service.equals("class")) {

                    this.logger.debug("path = " + path);

                    String id = path.substring(1, path.length()).split("/")[1];

                    requestParameter = new Properties();
                    requestParameter.setProperty("q", id);
                    requestParameter.setProperty("rows", "0");
                }

                if (requestParameter == null) {

                    RequestError requestError = new RequestError();
                    requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
                    requestError.setDescription("Malformed request!");
                    requestError.setError("BAD REQUEST");

                    httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    this.sendRequestError(httpServletResponse, requestError);
                }
                else {

                    // Resource Discovery Service API
                    if (Lookup.lookupAll(ResourceDiscoveryService.class).size() > 0) {

                        ResourceDiscoveryService resourceDiscoveryService = Lookup.lookup(ResourceDiscoveryService.class);
                        // init ResourceDiscoveryService
                        resourceDiscoveryService.init(this.config, service);

                        if (service.equals("search") || service.equals("class")) {

                            if (service.equals("search") && (requestParameter.getProperty("q").equals("") || requestParameter.getProperty("q").equals("*") || requestParameter.getProperty("q").equals("*:*") ||
                                    (!requestParameter.getProperty("rows").equals("") && Integer.parseInt(requestParameter.getProperty("rows")) > 50) ||
                                    (!requestParameter.getProperty("start").equals("") && Integer.parseInt(requestParameter.getProperty("start")) > 20)) ) {

                                RequestError requestError = new RequestError();
                                requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
                                requestError.setDescription("Malformed request!");
                                requestError.setError("BAD REQUEST");

                                httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                                this.sendRequestError(httpServletResponse, requestError);
                            }
                            else {
                                // Query
                                try {

                                    if (this.format.equals("html")) {

                                        Properties renderParameters = new Properties();
                                        renderParameters.setProperty("lang", this.language);
                                        renderParameters.setProperty("service", service);
                                        renderParameters.setProperty("isTUintern", Boolean.toString(this.isTUintern));
                                        renderParameters.setProperty("isUBintern", Boolean.toString(this.isUBintern));
                                        renderParameters.setProperty("debug", Boolean.toString(debug));

                                        String mode = "";
                                        if (httpServletRequest.getParameter("mode") != null) {

                                            mode = httpServletRequest.getParameter("mode");
                                        }
                                        renderParameters.setProperty("mode", mode);

                                        httpServletResponse.setContentType("text/html;charset=UTF-8");
                                        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                        httpServletResponse.getWriter().println(resourceDiscoveryService.getSearchResultsAsHTML(requestParameter, renderParameters));
                                    }

                                    if (this.format.equals("json")) {

                                        httpServletResponse.setContentType("application/json");
                                        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                        httpServletResponse.getWriter().println(resourceDiscoveryService.getSearchResultsAsJSON(requestParameter));
                                    }

                                    if (this.format.equals("xml")) {

                                        httpServletResponse.setContentType("application/xml");
                                        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                        httpServletResponse.getWriter().println(resourceDiscoveryService.getSearchResultsAsXML(requestParameter));
                                    }
                                }
                                catch (RDSException e) {

                                    this.logger.error("[" + this.config.getProperty("service.name") + "] Exception: " + HttpServletResponse.SC_SERVICE_UNAVAILABLE + " - " + e.getMessage());

                                    RequestError requestError = new RequestError();
                                    requestError.setCode(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                    requestError.setDescription(e.getMessage());
                                    requestError.setError("SERVICE_UNAVAILABLE");

                                    httpServletResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                    this.sendRequestError(httpServletResponse, requestError);
                                }
                            }
                        }
                        else if (service.equals("typeahead")) {

                            String prefix = "";
                            if (httpServletRequest.getParameter("q") != null) {
                                prefix = httpServletRequest.getParameter("q");
                            }

                            try {

                                String json = resourceDiscoveryService.getSuggestions(prefix);

                                httpServletResponse.setHeader("Access-Control-Allow-Origin", "*");
                                httpServletResponse.setContentType("application/json;charset=utf-8");
                                httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                httpServletResponse.getWriter().println(json);
                            } catch (RDSException e) {

                                this.logger.error("[" + this.config.getProperty("service.name") + "] Exception: " + HttpServletResponse.SC_SERVICE_UNAVAILABLE + " - " + e.getMessage());

                                RequestError requestError = new RequestError();
                                requestError.setCode(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                requestError.setDescription(e.getMessage());
                                requestError.setError("SERVICE_UNAVAILABLE");

                                httpServletResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                this.sendRequestError(httpServletResponse, requestError);
                            }
                        } else {

                            // nix
                        }

                    } else {

                        this.logger.error("[" + this.config.getProperty("service.name") + "] Exception: " + HttpServletResponse.SC_SERVICE_UNAVAILABLE + " - No ResourceDiscoveryService configured");

                        RequestError requestError = new RequestError();
                        requestError.setCode(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                        requestError.setDescription("Resource Discovery Service not configured!");
                        requestError.setError("SERVICE_UNAVAILABLE");

                        httpServletResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                        this.sendRequestError(httpServletResponse, requestError);
                    }
                }
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

    private Properties handleRequestParameters(HttpServletRequest httpServletRequest) {

        String q = "";
        if (httpServletRequest.getParameter("q") != null) {
            q = httpServletRequest.getParameter("q");
        }
        String fq = "";
        if (httpServletRequest.getParameterValues("fq") != null) {
            for (int i = 0; i < httpServletRequest.getParameterValues("fq").length; i++) {
                fq += httpServletRequest.getParameterValues("fq")[i];
                if (i < httpServletRequest.getParameterValues("fq").length - 1) {
                    fq += ";";
                }
            }
        }
        String rq = "";
        if (httpServletRequest.getParameterValues("rq") != null) {
            for (int i = 0; i < httpServletRequest.getParameterValues("rq").length; i++) {
                rq += httpServletRequest.getParameterValues("rq")[i];
                if (i < httpServletRequest.getParameterValues("rq").length - 1) {
                    rq += ";";
                }
            }
        }
        String start = "";
        if (httpServletRequest.getParameter("start") != null) {
            start = httpServletRequest.getParameter("start");
        }
        String rows = "";
        if (httpServletRequest.getParameter("rows") != null) {
            rows = httpServletRequest.getParameter("rows");
        }
        String sort = "";
        if (httpServletRequest.getParameter("sort") != null) {
            sort = httpServletRequest.getParameter("sort");
        }
        String group = "";
        if (httpServletRequest.getParameter("group") != null) {
            group = httpServletRequest.getParameter("group");
        }
        // RDS: lokaler Katalog-Bestand
        String local = "0";
        if (httpServletRequest.getParameter("local") != null && httpServletRequest.getParameter("local").equals("1")) {
            local = "1";
        }
        // RDS: lokaler Bestand inkl. E-Holdings
        String holdings = "0";
        if (httpServletRequest.getParameter("holdings") != null && httpServletRequest.getParameter("holdings").equals("1")) {
            holdings = "1";
        }
        // record request
        String ids = "";
        if (httpServletRequest.getParameter("ids") != null) {
            ids = httpServletRequest.getParameter("ids");
        }

        Properties requestParameter = new Properties();
        requestParameter.setProperty("q", q);
        requestParameter.setProperty("ids", ids);
        requestParameter.setProperty("start", start);
        requestParameter.setProperty("rows", rows);
        requestParameter.setProperty("sort", sort);
        requestParameter.setProperty("fq", fq);
        requestParameter.setProperty("rq", rq);
        requestParameter.setProperty("group", group);
        requestParameter.setProperty("local", local);
        requestParameter.setProperty("holdings", holdings);

        if (httpServletRequest.getParameter("news") != null && httpServletRequest.getParameter("news").equals("false")) {
            requestParameter.setProperty("news", "false");
        } else {
            requestParameter.setProperty("news", "true");
        }

        if (httpServletRequest.getParameter("exp") != null && httpServletRequest.getParameter("exp").equals("false")) {
            requestParameter.setProperty("exp", "false");
        } else {
            requestParameter.setProperty("exp", "true");
        }

        if (httpServletRequest.getParameter("eonly") != null && httpServletRequest.getParameter("eonly").equals("true")) {
            requestParameter.setProperty("eonly", "true");
        } else {
            requestParameter.setProperty("eonly", "false");
        }

        return requestParameter;
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
