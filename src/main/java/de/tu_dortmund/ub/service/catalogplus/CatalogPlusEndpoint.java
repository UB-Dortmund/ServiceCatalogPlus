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

import javax.json.Json;
import javax.json.JsonReader;
import javax.mail.MessagingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.*;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.Properties;

public class CatalogPlusEndpoint extends HttpServlet {

    // Configuration
    private Properties config = null;
    private Logger logger = null;

    public CatalogPlusEndpoint() throws IOException {

        this("conf/config.properties");
    }

    public CatalogPlusEndpoint(String conffile) throws IOException {

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
        this.logger = Logger.getLogger(CatalogPlusEndpoint.class.getName());

        this.logger.info("[" + this.config.getProperty("service.name") + "] " + "Starting 'CatalogPlusEndpoint' ...");
        this.logger.info("[" + this.config.getProperty("service.name") + "] " + "conf-file = " + conffile);
        this.logger.info("[" + this.config.getProperty("service.name") + "] " + "log4j-conf-file = " + this.config.getProperty("service.log4j-conf"));
    }

    public void doOptions(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {

        httpServletResponse.setHeader("Access-Control-Allow-Methods", config.getProperty("Access-Control-Allow-Methods"));
        httpServletResponse.addHeader("Access-Control-Allow-Headers", config.getProperty("Access-Control-Allow-Headers"));
        httpServletResponse.setHeader("Accept", config.getProperty("Accept"));
        httpServletResponse.setHeader("Access-Control-Allow-Origin", config.getProperty("Access-Control-Allow-Origin"));
        httpServletResponse.setHeader("Cache-Control", config.getProperty("Cache-Control"));

        httpServletResponse.getWriter().println();
    }

    public void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException {

        String format = null;
        String language = null;

        boolean isTUintern = false;
        boolean isUBintern = false;
        boolean is52bIBA = false;

        httpServletResponse.setHeader("Access-Control-Allow-Origin", config.getProperty("Access-Control-Allow-Origin"));
        httpServletResponse.setHeader("Cache-Control", config.getProperty("Cache-Control"));

        // analyse request path to define the service
        String path = httpServletRequest.getPathInfo();

        String service = "";

        if (path != null) {

            String[] params = path.substring(1, path.length()).split("/");

            if (params.length == 1) {
                service = params[0];
            }
            else if (path.startsWith("katalog/titel/")) {
                service = "api";
            }
        }

        this.logger.debug("service = " + service);

        if (!service.contains("typeahead")) {
            this.logger.info("PathInfo = " + httpServletRequest.getPathInfo());
            this.logger.info("QueryString = " + httpServletRequest.getQueryString());
        }

        // analyse ip range
        String ips = httpServletRequest.getHeader("X-Forwarded-For");

        isTUintern = AnalyseIPRange.analyseAccessRights(ips, config.getProperty("service.iprange.tu"), config.getProperty("service.iprange.tu.exceptions"));
        isUBintern = AnalyseIPRange.analyseAccessRights(ips, config.getProperty("service.iprange.ub"), config.getProperty("service.iprange.ub.exceptions"));
        is52bIBA = AnalyseIPRange.analyseAccessRights(ips, config.getProperty("service.iprange.ub.52bIBAs"), config.getProperty("service.iprange.ub.52bIBAs.exceptions"));

        if (!service.contains("typeahead")) {
            this.logger.info("[" + this.config.getProperty("service.name") + "] " + "Where is it from? " + httpServletRequest.getHeader("X-Forwarded-For") + ", " + isTUintern + ", " + isUBintern + ", " + is52bIBA);
        }

        // format
        format = "html";

        if (httpServletRequest.getParameter("format") != null && !httpServletRequest.getParameter("format").equals("")) {

            format = httpServletRequest.getParameter("format");
        }
        else {

            Enumeration<String> headerNames = httpServletRequest.getHeaderNames();
            while ( headerNames.hasMoreElements() ) {
                String headerNameKey = headerNames.nextElement();

                if (headerNameKey.equals("Accept")) {

                    if (!service.contains("typeahead")) {
                        this.logger.info("headerNameKey = " + httpServletRequest.getHeader(headerNameKey));
                    }

                    if (httpServletRequest.getHeader( headerNameKey ).contains("text/html")) {
                        format = "html";
                    }
                    else if (httpServletRequest.getHeader( headerNameKey ).contains("application/xml")) {
                        format = "xml";
                    }
                    else if (httpServletRequest.getHeader( headerNameKey ).contains("application/json")) {
                        format = "json";
                    }
                }
                if (headerNameKey.equals("Accept-Language")) {
                    language = httpServletRequest.getHeader( headerNameKey );
                    this.logger.debug("[" + config.getProperty("service.name") + "] " + "Accept-Language: " + language);
                }
            }
        }

        if (!service.contains("typeahead")) {
            this.logger.info("format = " + format);
        }

        // language
        if (language != null && language.startsWith("de")) {
            language = "de";
        } else if (language != null && language.startsWith("en")) {
            language = "en";
        } else if (httpServletRequest.getParameter("l") != null) {
            language = httpServletRequest.getParameter("l");
        } else {
            language = "de";
        }

        this.logger.info("language = " + language);

        // Debugging
        boolean debug = false;

        if (httpServletRequest.getParameter("debug") != null && httpServletRequest.getParameter("debug").equals("1")) {
            debug = true;
        }

        // is service valid?
        if (!service.equals("search") && !service.equals("typeahead") && !service.equals("getRecords") && !service.equals("getRecordCount") && !service.equals("classification")) {

            RequestError requestError = new RequestError();
            requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
            requestError.setDescription("The service '" + path + "' is not implemented. \n" + "\tQuery: " + httpServletRequest.getQueryString() + "\n" + "\tIP: " + httpServletRequest.getHeader("X-Forwarded-For"));
            requestError.setError("BAD REQUEST");

            httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
        }
        else if (!service.equals("typeahead") && !service.equals("getRecordCount") && !format.equals("html") && !isUBintern) {

            RequestError requestError = new RequestError();
            requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
            requestError.setDescription("You are not allowed to request results in '" + format + "'!");
            requestError.setError("BAD REQUEST");

            httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
        }
        else if ((service.equals("typeahead") || service.equals("getRecordCount")) && !format.equals("json")) {

            RequestError requestError = new RequestError();
            requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
            requestError.setDescription("Service '" + service + "' does not support format '" + format + "'!");
            requestError.setError("BAD REQUEST");

            httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
        }
        else {

            try {

                if (service.equals("search") || service.equals("getRecords") || service.equals("typeahead") || service.equals("getRecordCount")) {

                    // Query
                    Properties requestParameter = this.handleRequestParameters(httpServletRequest);
                    requestParameter.setProperty("lang", language);

                    // Resource Discovery Service API
                    if (Lookup.lookupAll(ResourceDiscoveryService.class).size() > 0) {

                        ResourceDiscoveryService resourceDiscoveryService = Lookup.lookup(ResourceDiscoveryService.class);
                        // init ResourceDiscoveryService
                        resourceDiscoveryService.init(this.config, service);

                        if (service.equals("search")) {

                            // TODO Diese Prüfung muss in RDSSummon!!!
                            int rows = 20;
                            if (requestParameter.getProperty("rows") != null && !requestParameter.getProperty("rows").equals("")) {

                                rows = Integer.parseInt(requestParameter.getProperty("rows"));
                            }

                            if (requestParameter.getProperty("q").equals("") || requestParameter.getProperty("q").equals("*") || requestParameter.getProperty("q").equals("*:*") ||
                                    (requestParameter.getProperty("rows") != null && !requestParameter.getProperty("rows").equals("") && Integer.parseInt(requestParameter.getProperty("rows")) > 50) ||
                                    (requestParameter.getProperty("start") != null && !requestParameter.getProperty("start").equals("") && Integer.parseInt(requestParameter.getProperty("start")) > 1000 / rows)) {

                                RequestError requestError = new RequestError();
                                requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
                                requestError.setDescription("Malformed request! \n" + "\tQuery: " + httpServletRequest.getQueryString() + "\n" + "\tIP: " + httpServletRequest.getHeader("X-Forwarded-For"));
                                requestError.setError("BAD REQUEST");

                                httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                                this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
                            }
                            else {
                                // Query
                                try {

                                    if (format.equals("html")) {

                                        Properties renderParameters = new Properties();
                                        renderParameters.setProperty("lang", language);
                                        renderParameters.setProperty("service", service);
                                        renderParameters.setProperty("isTUintern", Boolean.toString(isTUintern));
                                        renderParameters.setProperty("isUBintern", Boolean.toString(isUBintern));
                                        renderParameters.setProperty("is52bIBA", Boolean.toString(is52bIBA));
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

                                    if (format.equals("json")) {

                                        if (!isUBintern) {
                                            this.logger.error("[" + this.config.getProperty("service.name") + "] Uncontrolled JSON request! ");
                                        }

                                        Thread.sleep(150);

                                        httpServletResponse.setContentType("application/json;charset=UTF-8");
                                        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                        httpServletResponse.getWriter().println(resourceDiscoveryService.getSearchResultsAsJSON(requestParameter));
                                    }

                                    if (format.equals("xml")) {

                                        httpServletResponse.setContentType("application/xml;charset=UTF-8");
                                        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                        httpServletResponse.getWriter().println(resourceDiscoveryService.getSearchResultsAsXML(requestParameter));
                                    }
                                }
                                catch (RDSException e) {

                                    this.logger.error("[" + this.config.getProperty("service.name") + "] Exception: " + HttpServletResponse.SC_SERVICE_UNAVAILABLE + " - " + e.getMessage());

                                    RequestError requestError = new RequestError();
                                    requestError.setCode(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                    requestError.setDescription(e.getMessage() + " \n" + "\tQuery: " + httpServletRequest.getQueryString() + "\n" + "\tIP: " + httpServletRequest.getHeader("X-Forwarded-For"));
                                    requestError.setError("SERVICE_UNAVAILABLE");

                                    httpServletResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                    this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
                                }
                            }
                        }
                        else if (service.equals("getRecordCount")) {

                            if (httpServletRequest.getParameter("q") != null) {

                                int rows = 0;
                                if (requestParameter.getProperty("rows") != null && !requestParameter.getProperty("rows").equals("")) {

                                    rows = Integer.parseInt(requestParameter.getProperty("rows"));
                                }

                                if (requestParameter.getProperty("q").equals("") || requestParameter.getProperty("q").equals("*") || requestParameter.getProperty("q").equals("*:*") ||
                                        (requestParameter.getProperty("rows") != null && !requestParameter.getProperty("rows").equals("") && Integer.parseInt(requestParameter.getProperty("rows")) > 50) ||
                                        (requestParameter.getProperty("start") != null && !requestParameter.getProperty("start").equals("") && Integer.parseInt(requestParameter.getProperty("start")) > 1000 / rows)) {

                                    RequestError requestError = new RequestError();
                                    requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
                                    requestError.setDescription("Malformed request! \n" + "\tQuery: " + httpServletRequest.getQueryString() + "\n" + "\tIP: " + httpServletRequest.getHeader("X-Forwarded-For"));
                                    requestError.setError("BAD REQUEST");

                                    httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                                    this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
                                } else {
                                    // Query
                                    try {

                                        if (format.equals("json")) {

                                            if (!isUBintern) {
                                                this.logger.error("[" + this.config.getProperty("service.name") + "] Uncontrolled JSON request! ");
                                            }

                                            String resultsJsonString = resourceDiscoveryService.getSearchResultsAsJSON(requestParameter);

                                            // TODO parse recordCount
                                            JsonReader rdr = Json.createReader(new StringReader(resultsJsonString));

                                            String recordCount = rdr.readObject().getJsonNumber("recordCount").toString();

                                            String jsonString = "{";
                                            jsonString += "\"recordCount\" : \"" + recordCount + "\"";
                                            jsonString += "}";

                                            this.logger.info("jsonString = " + jsonString);

                                            Thread.sleep(150);

                                            httpServletResponse.setContentType("application/json;charset=UTF-8");
                                            httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                            httpServletResponse.getWriter().println(jsonString);
                                        }
                                    } catch (RDSException e) {

                                        this.logger.error("[" + this.config.getProperty("service.name") + "] Exception: " + HttpServletResponse.SC_SERVICE_UNAVAILABLE + " - " + e.getMessage());

                                        RequestError requestError = new RequestError();
                                        requestError.setCode(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                        requestError.setDescription(e.getMessage() + " \n" + "\tQuery: " + httpServletRequest.getQueryString() + "\n" + "\tIP: " + httpServletRequest.getHeader("X-Forwarded-For"));
                                        requestError.setError("SERVICE_UNAVAILABLE");

                                        httpServletResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                        this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
                                    }
                                }
                            }
                            else {

                                RequestError requestError = new RequestError();
                                requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
                                requestError.setDescription("Malformed request! \n" + "\tQuery: " + httpServletRequest.getQueryString() + "\n" + "\tIP: " + httpServletRequest.getHeader("X-Forwarded-For"));
                                requestError.setError("BAD REQUEST");

                                httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                                this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
                            }
                        }
                        else if (service.equals("getRecords")) {

                            if (httpServletRequest.getQueryString() != null && !httpServletRequest.getQueryString().equals("")) {

                                String institution_param = "";
                                if (httpServletRequest.getQueryString().contains("fq=Institution")) {

                                    for (String r : httpServletRequest.getParameterMap().keySet()) {

                                        if (r.equals("fq") && httpServletRequest.getParameter(r).startsWith("Institution")) {
                                            institution_param = "&fq=" + httpServletRequest.getParameter(r);
                                            break;
                                        }
                                    }
                                    this.logger.debug("[" + this.config.getProperty("service.name") + "] " + "institution_param: " + institution_param);
                                }

                                try {

                                    if (format.equals("html")) {

                                        httpServletResponse.setContentType("text/html;charset=UTF-8");

                                        Properties renderParameters = new Properties();
                                        renderParameters.setProperty("lang", language);
                                        renderParameters.setProperty("service", service);
                                        renderParameters.setProperty("isTUintern", Boolean.toString(isTUintern));
                                        renderParameters.setProperty("isUBintern", Boolean.toString(isUBintern));
                                        renderParameters.setProperty("is52bIBA", Boolean.toString(is52bIBA));
                                        renderParameters.setProperty("debug", Boolean.toString(debug));

                                        renderParameters.setProperty("recordset", institution_param);

                                        String mode = "";
                                        if (httpServletRequest.getParameter("mode") != null) {

                                            mode = httpServletRequest.getParameter("mode");
                                        }
                                        renderParameters.setProperty("mode", mode);
                                        if (mode.equals("simplehit")) {

                                            renderParameters.setProperty("getRecordsBaseURL", httpServletRequest.getRequestURL().toString() + "?ids=");
                                        }

                                        if (mode.equals("embedded") /*|| mode.equals("simplehit")*/) {

                                            httpServletResponse.setContentType("application/xml;charset=UTF-8");
                                        }

                                        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                        httpServletResponse.getWriter().println(resourceDiscoveryService.getSearchResultsAsHTML(requestParameter, renderParameters));
                                    }

                                    if (format.equals("json")) {

                                        httpServletResponse.setContentType("application/json;charset=UTF-8");
                                        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                        httpServletResponse.getWriter().println(resourceDiscoveryService.getSearchResultsAsJSON(requestParameter));
                                    }

                                    if (format.equals("xml")) {

                                        httpServletResponse.setContentType("application/xml;charset=UTF-8");
                                        httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                                        httpServletResponse.getWriter().println(resourceDiscoveryService.getSearchResultsAsXML(requestParameter));
                                    }
                                }
                                catch (RDSException e) {

                                    this.logger.error("[" + this.config.getProperty("service.name") + "] Exception: " + HttpServletResponse.SC_SERVICE_UNAVAILABLE + " - " + e.getMessage());

                                    RequestError requestError = new RequestError();
                                    requestError.setCode(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                    requestError.setDescription(e.getMessage() + " \n" + "\tQuery: " + httpServletRequest.getQueryString() + "\n" + "\tIP: " + httpServletRequest.getHeader("X-Forwarded-For"));
                                    requestError.setError("SERVICE_UNAVAILABLE");

                                    httpServletResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                    this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
                                }
                            }
                            else {

                                this.logger.error("[" + this.config.getProperty("service.name") + "] Exception: " + HttpServletResponse.SC_BAD_REQUEST + " - " + "Bad Request!");

                                RequestError requestError = new RequestError();
                                requestError.setCode(HttpServletResponse.SC_BAD_REQUEST);
                                requestError.setDescription("No record ids defined!");
                                requestError.setError("BAD_REQUEST");

                                httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                                this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
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
                                this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
                            }
                        }
                    }
                    else {

                        this.logger.error("[" + this.config.getProperty("service.name") + "] Exception: " + HttpServletResponse.SC_SERVICE_UNAVAILABLE + " - No ResourceDiscoveryService configured");

                        RequestError requestError = new RequestError();
                        requestError.setCode(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                        requestError.setDescription("Resource Discovery Service not configured!");
                        requestError.setError("SERVICE_UNAVAILABLE");

                        httpServletResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                        this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
                    }
                }
                else {

                    if (Lookup.lookupAll(VirtualClassificationSystem.class).size() > 0) {

                        String notation = "";

                        if (httpServletRequest.getParameter("class") != null && !httpServletRequest.getParameter("class").equals("")) {

                            notation = httpServletRequest.getParameter("class");
                        }

                        VirtualClassificationSystem virtualClassificationSystem = Lookup.lookup(VirtualClassificationSystem.class);
                        // init VirtualClassificationSystem
                        virtualClassificationSystem.init(this.config);

                        if (format.equals("html")) {

                            Properties renderParameters = new Properties();
                            renderParameters.setProperty("lang", language);
                            renderParameters.setProperty("service", service);
                            renderParameters.setProperty("notation", notation);
                            renderParameters.setProperty("isTUintern", Boolean.toString(isTUintern));
                            renderParameters.setProperty("isUBintern", Boolean.toString(isUBintern));
                            renderParameters.setProperty("is52bIBA", Boolean.toString(is52bIBA));
                            renderParameters.setProperty("debug", Boolean.toString(debug));

                            String mode = "";
                            if (httpServletRequest.getParameter("mode") != null) {

                                mode = httpServletRequest.getParameter("mode");
                            }
                            renderParameters.setProperty("mode", mode);

                            String queryString = "";
                            if (httpServletRequest.getParameter("queryString") != null) {

                                queryString = httpServletRequest.getParameter("queryString");
                            }
                            renderParameters.setProperty("queryString", URLDecoder.decode(queryString,"UTF-8"));

                            httpServletResponse.setContentType("text/html;charset=UTF-8");
                            httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                            httpServletResponse.getWriter().println(virtualClassificationSystem.getClassAsHTML(notation, renderParameters));
                        }

                        if (format.equals("json")) {

                            httpServletResponse.setContentType("application/json;charset=UTF-8");
                            httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                            httpServletResponse.getWriter().println(virtualClassificationSystem.getClassAsJSON(notation));
                        }

                        if (format.equals("xml")) {

                            httpServletResponse.setContentType("application/xml;charset=UTF-8");
                            httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                            httpServletResponse.getWriter().println(virtualClassificationSystem.getClassAsXML(notation));
                        }
                    }
                    else {

                        this.logger.error("[" + this.config.getProperty("service.name") + "] Exception: " + HttpServletResponse.SC_SERVICE_UNAVAILABLE + " - No VirtualClassificationSystem configured");

                        RequestError requestError = new RequestError();
                        requestError.setCode(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                        requestError.setDescription("Virtual Classification System not configured!");
                        requestError.setError("SERVICE_UNAVAILABLE");

                        httpServletResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                        this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
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
                this.sendRequestError(httpServletResponse, requestError, format, language, isUBintern, isTUintern, is52bIBA);
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
        // smaller search for record counts in classification search
        String type = "full";
        if (httpServletRequest.getParameter("type") != null && httpServletRequest.getParameter("type").equals("light")) {
            type = "light";
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
        requestParameter.setProperty("type", type);

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

    private void sendRequestError(HttpServletResponse httpServletResponse, RequestError requestError, String format, String language, boolean isUBintern, boolean isTUintern, boolean is52bIBA) {

        if (requestError.getCode() == HttpServletResponse.SC_BAD_REQUEST || requestError.getCode() == HttpServletResponse.SC_SERVICE_UNAVAILABLE || requestError.getCode() == HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {

            try {

                Mailer mailer = new Mailer(this.config.getProperty("service.mailer.conf"));
                mailer.postMail("[" + this.config.getProperty("service.name") + "] Exception: " + requestError.getCode(), requestError.getDescription());

            } catch (MessagingException | IOException e1) {

                this.logger.error(e1.getMessage(), e1.getCause());
            }
        }

        ObjectMapper mapper = new ObjectMapper();

        httpServletResponse.setContentType("application/json;charset=UTF-8");

        try {

            if (format.equals("html")) {

                if (Lookup.lookupAll(ObjectToHtmlTransformation.class).size() > 0) {

                    try {
                        ObjectToHtmlTransformation htmlTransformation = Lookup.lookup(ObjectToHtmlTransformation.class);
                        // init transformator
                        htmlTransformation.init(this.config);

                        Properties parameters = new Properties();
                        parameters.setProperty("lang", language);
                        parameters.setProperty("isTUintern", Boolean.toString(isTUintern));
                        parameters.setProperty("isUBintern", Boolean.toString(isUBintern));
                        parameters.setProperty("is52bIBA", Boolean.toString(is52bIBA));

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
                    format = "json";
                }
            }

            // XML-Ausgabe mit JAXB
            if (format.equals("xml")) {

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
            if (format.equals("json")) {

                httpServletResponse.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(httpServletResponse.getWriter(), requestError);
            }
        }
        catch (Exception e) {

            e.printStackTrace();
        }
    }
}
