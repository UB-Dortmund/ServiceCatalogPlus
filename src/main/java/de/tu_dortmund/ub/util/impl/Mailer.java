package de.tu_dortmund.ub.util.impl;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.*;
import java.util.Date;
import java.util.Properties;

/**
 * Created by cihabe on 04.04.14.
 */
public class Mailer {

    private String propfile_api  = "";

    private Properties apiProperties = new Properties();

    private Logger logger = Logger.getLogger(Mailer.class.getName());

    public Mailer() throws IOException {

        this("../conf/mailer.properties");

    }

    public Mailer(String propfile_api) throws IOException {

        this.propfile_api = propfile_api;

        // Init properties
        try {
            InputStream inputStream = new FileInputStream(propfile_api);

            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));

                try {
                    apiProperties.load(reader);

                } finally {
                    reader.close();
                }
            }
            finally {
                inputStream.close();
            }
        }
        catch (IOException e) {
            System.out.println("FATAL ERROR: Die Datei '" + propfile_api + "' konnte nicht geöffnet werden!");
        }

        // init logger
        PropertyConfigurator.configure(apiProperties.getProperty("service.log4j-conf"));

        logger.info("Starting Mailer ... ");
        logger.info("conf-file = " + propfile_api);
        logger.info("log4j-conf-file = " + apiProperties.getProperty("service.log4j-conf"));
    }

    public void postMail( String subject, String message ) throws MessagingException {

        String to = this.apiProperties.getProperty("to");
        String from = this.apiProperties.getProperty("from");
        String host = this.apiProperties.getProperty("host");

        // create some properties and get the default Session
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.debug", false);

        Session session = Session.getInstance(props, null);
        session.setDebug(true);

        try {
            // create a message
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from));
            //InternetAddress[] address = {new InternetAddress(to)};
            msg.setRecipients(Message.RecipientType.TO, to);
            msg.setSubject(subject);
            msg.setSentDate(new Date());
            if (message != null) {
                msg.setText(message);
            }
            else {
                msg.setText("");
            }

            Transport.send(msg);

        } catch (MessagingException mex) {

            mex.printStackTrace();
            System.out.println();
            Exception ex = mex;
            do {
                if (ex instanceof SendFailedException) {
                    SendFailedException sfex = (SendFailedException)ex;
                    Address[] invalid = sfex.getInvalidAddresses();
                    if (invalid != null) {
                        for (int i = 0; i < invalid.length; i++)
                            this.logger.debug("Invalid Addresses" + invalid[i]);
                    }
                    Address[] validUnsent = sfex.getValidUnsentAddresses();
                    if (validUnsent != null) {
                        for (int i = 0; i < validUnsent.length; i++)
                            System.out.println("ValidUnsent Addresses" + validUnsent[i]);
                    }
                    Address[] validSent = sfex.getValidSentAddresses();
                    if (validSent != null) {
                        for (int i = 0; i < validSent.length; i++)
                            System.out.println("ValidSent Addresses" + validSent[i]);
                    }
                }
                System.out.println();
                if (ex instanceof MessagingException)
                    ex = ((MessagingException)ex).getNextException();
                else
                    ex = null;
            } while (ex != null);
        }
    }
}
