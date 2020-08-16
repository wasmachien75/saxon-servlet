package tv.mediagenix.xslt.transformer;

import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Spark;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import static spark.Spark.*;

public class Server {

    private final String ENDPOINT = "/transform";
    private final String INPUT_KEY = "xml";
    private final String XSL_KEY = "xsl";
    private Logger logger = LoggerFactory.getLogger(Server.class);
    private ServerOptions options;

    public static void main(String[] args) {
        try {
            ServerOptions options = ServerOptions.fromArgs(args);
            Server.newServer(options);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            System.exit(-1);
        }
    }

    private Server(ServerOptions options) {
        if (options == null) {
            this.options = new ServerOptions();
        } else {
            this.options = options;
        }
    }

    public static Server newServer(ServerOptions options) {
        Server s = new Server(options);
        s.configureKeystore();
        s.configureRoutes();
        s.configureExceptions();
        s.configureFilters();
        return s;
    }

    private void configureFilters() {
        before("/*", (req, res) -> {
            if (!isAuthenticated()) {
                halt(401);
            }
        });


        before("/*", (req, res) -> {
            res.raw().setHeader("Server", "/");
        });
    }

    private boolean isAuthenticated() {
        return true;
    }

    private void configureExceptions() {
        exception(InvalidRequestException.class, (e, req, res) -> Server.handleException(e, res, 400));
        exception(TransformationException.class, (e, req, res) -> Server.handleException(e.getCause() != null ? e.getCause() : e, res, 400));
        exception(Exception.class, (e, req, res) -> Server.handleException(e, res, 500));
        notFound((req, res) -> {
            res.type("application/json");
            res.status(404);
            return ("\"404 Not Found\"");
        });
    }

    private void configureKeystore() {
        String keyStoreProp = System.getProperty("keystore");
        if (keyStoreProp != null) {
            String keyStorePassw = System.getProperty("keystorePassword");
            if (keyStorePassw == null) keyStorePassw = "";
            secure(keyStoreProp, keyStorePassw, null, null);
        }
    }

    private void configureRoutes() {
        port(options.getPort());
        post(ENDPOINT, this::handleRequest);
    }

    Object handleRequest(Request req, Response res) throws Exception {
        long startTime = System.currentTimeMillis();
        req.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement("saxon"));
        try (InputStream input = getStreamFromRequestByKey(req, INPUT_KEY)) {
            try (InputStream stylesheet = getStreamFromRequestByKey(req, XSL_KEY)) {
                SaxonTransformer tf = options.getConfigFile() != null
                        ? new SaxonTransformer(options.getConfigFile())
                        : new SaxonTransformer(options.isInsecure());
                ByteArrayOutputStream writeStream = new ByteArrayOutputStream();
                SerializationProperties props = tf.transform(input, stylesheet, writeStream);
                res.header("Content-type", props.contentType());
                writeStream.writeTo(res.raw().getOutputStream());
                res.raw().getOutputStream().close();
                return res;
            }
        } finally {
            logger.info(String.format("Finished request in %s milliseconds", System.currentTimeMillis() - startTime));
        }
    }

    private InputStream getStreamFromRequestByKey(Request req, String key) throws InvalidRequestException, IOException, ServletException {
        try {
            Part part = req.raw().getPart(key);
            if (part == null) {
                throw new InvalidRequestException(String.format("No part found for key \"%s\"", key));
            }
            return getStreamFromPart(part);
        } catch (ServletException e) {
            throw new InvalidRequestException(String.format("Could not read parts for key \"%s\" - did you forget to attach a file? (%s)", key, e.getMessage()));
        }
    }

    private InputStream getStreamFromPart(Part part) throws IOException, InvalidRequestException {
        switch (part.getContentType().toLowerCase()) {
            case "application/xml":
                return part.getInputStream();
            case "application/gzip":
                return getZippedStreamFromPart(part.getInputStream());
        }
        throw new InvalidRequestException("Unexpected content type for part " + part.getName());
    }

    private InputStream getZippedStreamFromPart(InputStream input) throws InvalidRequestException {
        ByteArrayOutputStream writeStream = new ByteArrayOutputStream();
        try {
            GZIPInputStream s = new GZIPInputStream(input);
            int data = s.read();
            while (data != -1) {
                writeStream.write(data);
                data = s.read();
            }
            return new ByteArrayInputStream(writeStream.toByteArray());
        } catch (IOException e) {
            throw new InvalidRequestException(e.getMessage());
        }

    }

    private static Response handleException(Throwable e, Response res, int status) {
        ErrorMessage err = new ErrorMessage(e, status);
        res.status(status);
        res.type("application/json;charset=utf-8");
        res.body(new JsonTransformer().render(err));
        return res;
    }

    public void stop() {
        Spark.stop();
    }

}
