package ch.zhaw.mami;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Date;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.StreamingOutput;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import ch.zhaw.mami.db.AccessLevels;
import ch.zhaw.mami.db.AuthDB;
import ch.zhaw.mami.db.LogDB;
import ch.zhaw.mami.db.UploadDB;

import com.sun.jersey.multipart.FormDataParam;

@Path("/hdfs")
public class API {

    public RuntimeConfiguration runtimeConfiguration;
    private final static Logger logger = LogManager.getLogger(API.class);

    public static byte value;
    public static Object mutex = new Object();
    public static boolean next = false;

    private final AuthDB authDB;
    private final UploadDB uploadDB;
    private final LogDB logDB;

    public API() throws IOException {
        API.logger.entry();
        runtimeConfiguration = RuntimeConfiguration.getInstance();
        // System.out.println(runtimeConfiguration.getPathPrefix());
        authDB = runtimeConfiguration.getAuthDB();
        uploadDB = runtimeConfiguration.getUploadDB();
        logDB = runtimeConfiguration.getLogDB();
        API.logger.exit();
    }

    private Response accessError() {
        API.logger.entry();
        return API.logger.exit(Response.status(401)
                .entity("Invalid API token or insufficient access!")
                .type(MediaType.TEXT_PLAIN).build());
    }

    @Path("mgmt/accessLevel")
    @GET
    public Response accessLevel(@HeaderParam("X-API-KEY") final String apiKey) {
        API.logger.entry(apiKey);
        int accessLevel = authDB.getAccessLevel(apiKey);
        return API.logger.exit(Response.ok("" + accessLevel,
                MediaType.TEXT_PLAIN).build());
    }

    public ResponseBuilder allowCORS(final ResponseBuilder rb) {
        return API.logger.exit(rb.header("Access-Control-Allow-Origin", "*"));
    }

    @Path("fs/bin/{path:.+}")
    @GET
    public Response bin(@HeaderParam("X-API-KEY") final String apiKey,
            @PathParam("path") final String path) {
        API.logger.entry(apiKey, path);

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_READ)) {
                return API.logger.exit(API.logger.exit(accessError()));
            }

            logDB.insertLogEntry(path, "bin", authDB.getName(apiKey));

            if (!Util.validatePath(path)) {
                return API.logger
                        .exit(clientError("Invalid path (contains illegal characters or too long)"));
            }

            org.apache.hadoop.fs.Path pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix() + path);

            if (!runtimeConfiguration.getFileSystem().exists(pt)) {
                return generic404("File not found: " + pt.getName());
            }

            if (!runtimeConfiguration.getFileSystem().isFile(pt)) {
                return API.logger
                        .exit(generic404("Not a file: " + pt.getName()));
            }

            final InputStream is = runtimeConfiguration.getFileSystem()
                    .open(pt);

            StreamingOutput so = new StreamingOutput() {

                @Override
                public void write(final OutputStream os) throws IOException,
                        WebApplicationException {

                    byte[] chunk = new byte[runtimeConfiguration.getChunkSize()];
                    int read;
                    API.logger.trace("Reading chunks...");
                    while ((read = is.read(chunk)) > 0) {
                        API.logger.trace("got chunk of size: " + read);
                        os.write(chunk);
                        API.logger.trace("wrote chunk");
                    }
                    os.flush();

                }

            };

            return API.logger.exit(Response.ok(so)
                    .type(MediaType.APPLICATION_OCTET_STREAM).build());
        } catch (Exception ex) {
            API.logger.catching(ex);
            return API.logger.exit(internalError());
        }
    }

    @GET
    @Path("fs/check/{path:.+}")
    public Response check(@HeaderParam("X-API-KEY") final String apiKey,
            @PathParam("path") final String path) {

        boolean locked = false;
        boolean uploadEntryPresent = false;
        boolean filePresent = false;

        org.apache.hadoop.fs.Path pt = null;

        FSDataInputStream is = null;

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_READ)) {
                return API.logger.exit(accessError());
            }

            pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix() + path);

            logDB.insertLogEntry(path, "check", authDB.getName(apiKey));

            if (!Util.validatePath(path)) {
                return API.logger
                        .exit(clientError("Invalid path (contains illegal characters or too long)"));
            }

            if (uploadDB.uploadExists(pt.toString())) {
                uploadEntryPresent = true;
            }

            FileSystem fs = runtimeConfiguration.getFileSystem();

            if (fs.exists(pt)) {
                if (fs.isFile(pt)) {
                    filePresent = true;
                }
            }

            Document doc = new Document();
            doc.append("uploadEntryPresent", uploadEntryPresent);
            doc.append("filePresent", filePresent);
            doc.append("path", pt.toString());

            /* if none of those exist we can't do much checking */
            if (!uploadEntryPresent && !filePresent) {
                return API.logger.exit(Response.ok(doc.toJson(),
                        MediaType.APPLICATION_JSON).build());
            }

            /*
             * if the file is not present we can only return the upload entry.
             */
            if (!filePresent && uploadEntryPresent) {

                doc.append("uploadEntry",
                        uploadDB.getUploadEntry(pt.toString()));
                return API.logger.exit(Response.ok(doc.toJson(),
                        MediaType.APPLICATION_JSON).build());
            }

            /* at this point at least the file is present */
            locked = uploadDB.getLock(pt.toString());

            if (uploadEntryPresent) {
                doc.append("uploadEntry",
                        uploadDB.getUploadEntry(pt.toString()));
            }

            if (!locked) {
                /* if we couldn't acquire the lock then the file is busy */
                doc.append("locked", true);
                /* we're not going to read files that have locks */
                return API.logger.exit(Response.ok(doc.toJson(),
                        MediaType.APPLICATION_JSON).build());
            }
            else {
                doc.append("locked", false); // the file is not locked and we
                                             // hold the lock now.
            }

            is = runtimeConfiguration.getFileSystem().open(pt);

            MessageDigest md = MessageDigest.getInstance("SHA-1");

            byte[] chunk = new byte[runtimeConfiguration.getChunkSize()];

            int read = 0;

            while ((read = is.read(chunk)) > 0) {
                md.update(chunk, 0, read);
            }

            String digest = Util.byteArr2HexStr(md.digest());

            doc.append("fileSha1", digest);

            return API.logger.exit(Response.ok(doc.toJson(),
                    MediaType.APPLICATION_JSON).build());

        } catch (Exception ex) {
            API.logger.catching(ex);
            return API.logger.exit(internalError());
        } finally {
            boolean error = false;

            try {
                if (is != null) {
                    is.close();
                }
            } catch (Exception ex) {
                API.logger.catching(ex);
                error = true;
            }

            try {
                if (locked) {
                    uploadDB.releaseLock(pt.toString());
                }
            } catch (Exception ex) {
                API.logger.catching(ex);
                error = true;
            }

            if (error) {
                return API.logger.exit(internalError());
            }
        }

    }

    public boolean checkAccessLevel(final String apiKey, final int level) {

        int accessLevel = authDB.getAccessLevel(apiKey);
        if ((accessLevel & level) == level) {
            return true;
        }
        else {
            return false;
        }
    }

    private Response clientError(final String message) {
        API.logger.entry(message);
        return API.logger.exit(Response.status(499).entity(message)
                .type(MediaType.TEXT_PLAIN).build());
    }

    public Response generic404(final String message) {
        API.logger.entry(message);
        return API.logger.exit(Response.status(404).entity(message)
                .type(MediaType.TEXT_PLAIN).build());
    }

    public Response generic500(final String message) {
        API.logger.entry(message);
        return API.logger.exit(Response.status(500).entity(message)
                .type(MediaType.TEXT_PLAIN).build());
    }

    @SuppressWarnings("deprecation")
    private Response internalError() {
        API.logger.entry();
        API.logger.error("** INTERNAL ERROR **");
        return API.logger
                .exit(generic500("Internal Error - Please contact administrator. Timestamp: "
                        + new Date().toGMTString()));
    }

    @Path("fs/ls/{path:.+}")
    @GET
    public Response ls(@HeaderParam("X-API-KEY") final String apiKey,
            @PathParam("path") final String path) {

        API.logger.entry(apiKey, path);

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_READ)) {
                return API.logger.exit(accessError());
            }

            logDB.insertLogEntry(path, "ls", authDB.getName(apiKey));

            if (!Util.validatePath(path)) {
                return API.logger
                        .exit(clientError("Invalid path (contains illegal characters or too long)"));
            }

            org.apache.hadoop.fs.Path pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix() + path);

            FileSystem fs = runtimeConfiguration.getFileSystem();

            if (!fs.exists(pt)) {
                return API.logger.exit(generic404("Directory not found: "
                        + pt.getName()));
            }

            if (!fs.isDirectory(pt)) {
                return API.logger.exit(generic404("Not a directory: "
                        + pt.getName()));
            }

            RemoteIterator<LocatedFileStatus> files = fs.listFiles(pt, false);
            JSONArray arr = new JSONArray();
            while (files.hasNext()) {
                LocatedFileStatus lfs = files.next();
                arr.put(lfs.getPath().getName());
            }
            return API.logger.exit(allowCORS(
                    Response.ok(arr.toString(), MediaType.APPLICATION_JSON))
                    .build());
        } catch (Exception ex) {
            API.logger.catching(ex);
            return API.logger.exit(generic500("Internal Error"));
        }
    }

    @Path("fs/lsR/{path:.+}")
    @GET
    public Response lsR(@HeaderParam("X-API-KEY") final String apiKey,
            @PathParam("path") final String path) {

        API.logger.entry(apiKey, path);

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_READ)) {
                return API.logger.exit(accessError());
            }

            logDB.insertLogEntry(path, "lsR", authDB.getName(apiKey));

            if (!Util.validatePath(path)) {
                return API.logger
                        .exit(clientError("Invalid path (contains illegal characters or too long)"));
            }

            org.apache.hadoop.fs.Path pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix() + path);

            FileSystem fs = runtimeConfiguration.getFileSystem();

            if (!fs.exists(pt)) {
                return API.logger.exit(generic404("Directory not found: "
                        + pt.getName()));
            }

            if (!fs.isDirectory(pt)) {
                return API.logger.exit(generic404("Not a directory: "
                        + pt.getName()));
            }

            RemoteIterator<LocatedFileStatus> files = fs.listFiles(pt, true);
            JSONArray arr = new JSONArray();
            while (files.hasNext()) {
                LocatedFileStatus lfs = files.next();
                arr.put(lfs.getPath().toString());
            }

            return API.logger.exit(Response.ok(arr.toString(),
                    MediaType.TEXT_PLAIN).build());
        } catch (Exception ex) {
            API.logger.catching(ex);
            return API.logger.exit(internalError());
        }
    }

    @Path("fs/put/{path:.+}")
    @POST
    @Consumes({ MediaType.APPLICATION_OCTET_STREAM })
    public Response put(@HeaderParam("X-API-KEY") final String apiKey,
            @PathParam("path") final String path, final InputStream data) {

        API.logger.entry(apiKey, path, data);
        OutputStream os = null;
        org.apache.hadoop.fs.Path pt = null;
        boolean locked = false;

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_ADMIN)) {
                return API.logger.exit(accessError());
            }

            logDB.insertLogEntry(path, "put", authDB.getName(apiKey));

            if (!Util.validatePath(path)) {
                return API.logger
                        .exit(clientError("Invalid path (contains illegal characters or too long)"));
            }

            pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix() + path);

            locked = uploadDB.getLock(path);

            if (!locked) {
                return API.logger.exit(clientError("File is busy!"));
            }

            FileSystem fs = runtimeConfiguration.getFileSystem();
            os = fs.create(pt);

            byte chunk[] = new byte[runtimeConfiguration.getChunkSize()];
            int read;
            while ((read = data.read(chunk)) > 0) {
                os.write(chunk, 0, read);
            }

            os.flush();

            return API.logger.exit(Response.ok(pt.toString(),
                    MediaType.TEXT_PLAIN).build());
        } catch (Exception ex) {
            API.logger.catching(ex);
            uploadDB.insertError(pt.toString(), null, ex.getMessage());
            return API.logger.exit(internalError());
        } finally {

            boolean error = false;

            if (os != null) {
                try {
                    os.close();
                } catch (Exception ex) {
                    API.logger.catching(ex);
                    uploadDB.insertError(pt.toString(), null, ex.getMessage());
                    error = true;
                }
            }
            if (data != null) {
                try {
                    data.close();
                } catch (Exception ex) {
                    uploadDB.insertError(pt.toString(), null, ex.getMessage());
                    API.logger.catching(ex);
                    error = true;
                }
            }

            try {
                if (locked) {
                    uploadDB.releaseLock(pt.toString());
                }
            } catch (Exception ex) {
                API.logger.catching(ex);
                uploadDB.insertError(pt.toString(), null, ex.getMessage());
                error = true;
            }

            if (error) {
                return API.logger.exit(internalError());
            }
        }
    }

    @GET
    @javax.ws.rs.Path("fs/raw/{path:.+}")
    public Response raw(@HeaderParam("X-API-KEY") final String apiKey,
            @PathParam("path") final String path) {

        API.logger.entry(apiKey, path);

        BufferedReader br = null;

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_READ)) {
                return API.logger.exit(accessError());
            }

            logDB.insertLogEntry(path, "raw", authDB.getName(apiKey));

            if (!Util.validatePath(path)) {
                return API.logger
                        .exit(clientError("Invalid path (contains illegal characters or too long)"));
            }

            org.apache.hadoop.fs.Path pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix() + path);

            if (!runtimeConfiguration.getFileSystem().exists(pt)) {
                return API.logger.exit(generic404("File not found: "
                        + pt.getName()));
            }

            if (!runtimeConfiguration.getFileSystem().isFile(pt)) {
                return API.logger
                        .exit(generic404("Not a file: " + pt.getName()));
            }

            br = new BufferedReader(new InputStreamReader(runtimeConfiguration
                    .getFileSystem().open(pt)));

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line + "\n");
            }

            return API.logger.exit(allowCORS(
                    Response.ok(sb.toString(), MediaType.TEXT_PLAIN)).build());

        } catch (Exception ex) {
            API.logger.catching(ex);
            return API.logger.exit(internalError());
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Exception ex) {
                API.logger.catching(ex);
                return API.logger.exit(internalError());
            }
        }
    }

    @Path("mgmt/revoke")
    @POST
    public Response revoke(@HeaderParam("X-API-KEY") final String apiKey) {
        API.logger.entry(apiKey);
        authDB.revoke(apiKey);
        return API.logger.exit(Response.ok("OK", MediaType.TEXT_PLAIN).build());
    }

    @Path("fs/seq/bin/{path:.+}")
    @GET
    @Produces({ MediaType.APPLICATION_OCTET_STREAM })
    public Response seqBin(@PathParam("path") final String path,
            @QueryParam("fileName") final String fileName,
            @HeaderParam("X-API-KEY") final String apiKey) {
        API.logger.entry(path, fileName);

        SequenceFile.Reader seqReader = null;

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_READ)) {
                return API.logger.exit(accessError());
            }

            logDB.insertLogEntry(path, "seqbin", authDB.getName(apiKey));

            if (!Util.validatePath(path)) {
                return API.logger
                        .exit(clientError("Invalid path (contains illegal characters or too long)"));
            }

            org.apache.hadoop.fs.Path pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix() + path);

            seqReader = new SequenceFile.Reader(
                    runtimeConfiguration.getFSConfiguration(),
                    SequenceFile.Reader.file(pt));

            BytesWritable key = new BytesWritable();

            while (seqReader.next(key)) {
                String keyAsStr = new String(key.getBytes(), 0, key.getLength());
                if (keyAsStr.equals(fileName)) {
                    BytesWritable value = new BytesWritable();
                    seqReader.getCurrentValue(value);
                    seqReader.close();
                    return API.logger.exit(Response.ok(value.copyBytes(),
                            MediaType.APPLICATION_OCTET_STREAM).build());
                }
            }

            seqReader.close();

            return API.logger
                    .exit(generic404("File not found in sequence file!"));
        } catch (Exception ex) {
            API.logger.catching(ex);
            return API.logger.exit(internalError());
        } finally {
            if (seqReader != null) {
                try {
                    seqReader.close();
                } catch (Exception ex) {
                    API.logger.catching(ex);
                    return API.logger.exit(internalError());
                }
            }
        }

    }

    @GET
    @Path("fs/seq/check/{path:.+}")
    public Response seqCheck(@HeaderParam("X-API-KEY") final String apiKey,
            @PathParam("path") final String path,
            @QueryParam("fileName") final String fileName) {
        boolean locked = false;
        boolean uploadEntryPresent = false;
        boolean filePresent = false;

        org.apache.hadoop.fs.Path pt = null;
        SequenceFile.Reader seqReader = null;

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_READ)) {
                return API.logger.exit(accessError());
            }

            pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix() + path);

            logDB.insertLogEntry(path, "check", authDB.getName(apiKey));

            if (!Util.validatePath(path)) {
                return API.logger
                        .exit(clientError("Invalid path (contains illegal characters or too long)"));
            }

            if (!Util.validateFileName(fileName)) {
                return API.logger
                        .exit(clientError("Invalid file name (contains illegal characters or too long)"));
            }

            if (uploadDB.seqUploadExists(pt.toString(), fileName)) {
                uploadEntryPresent = true;
            }

            FileSystem fs = runtimeConfiguration.getFileSystem();

            if (fs.exists(pt)) {
                if (fs.isFile(pt)) {
                    filePresent = true;
                }
            }

            Document doc = new Document();
            doc.append("uploadEntryPresent", uploadEntryPresent);
            doc.append("filePresent", filePresent);
            doc.append("path", pt.toString());

            /* if none of those exist we can't do much checking */
            if (!uploadEntryPresent && !filePresent) {
                return API.logger.exit(Response.ok(doc.toJson(),
                        MediaType.APPLICATION_JSON).build());
            }

            /*
             * if the file is not present we can only return the upload entry.
             */
            if (!filePresent && uploadEntryPresent) {

                doc.append("uploadEntry",
                        uploadDB.getSeqUploadEntry(pt.toString(), fileName));
                return API.logger.exit(Response.ok(doc.toJson(),
                        MediaType.APPLICATION_JSON).build());
            }

            /* at this point at least the file is present */
            locked = uploadDB.getLock(pt.toString());

            if (uploadEntryPresent) {
                doc.append("uploadEntry",
                        uploadDB.getSeqUploadEntry(pt.toString(), fileName));
            }

            if (!locked) {
                /* if we couldn't acquire the lock then the file is busy */
                doc.append("locked", true);
                /* we're not going to read files that have locks */
                return API.logger.exit(Response.ok(doc.toJson(),
                        MediaType.APPLICATION_JSON).build());
            }
            else {
                doc.append("locked", false); // the file is not locked and we
                                             // hold the lock now.
            }

            seqReader = new SequenceFile.Reader(
                    runtimeConfiguration.getFSConfiguration(),
                    SequenceFile.Reader.file(pt));

            BytesWritable key = new BytesWritable();
            String digest = "";
            boolean fileInSeqFile = false;

            while (seqReader.next(key)) {
                String keyAsStr = new String(key.getBytes(), 0, key.getLength());
                if (keyAsStr.equals(fileName)) {
                    BytesWritable value = new BytesWritable();
                    seqReader.getCurrentValue(value);
                    MessageDigest md = MessageDigest.getInstance("SHA-1");
                    md.update(value.getBytes(), 0, value.getLength());
                    digest = Util.byteArr2HexStr(md.digest());
                    fileInSeqFile = true;
                    break;
                }
            }

            doc.append("fileInSeqFile", fileInSeqFile);
            doc.append("fileSha1", digest);

            return API.logger.exit(Response.ok(doc.toJson(),
                    MediaType.APPLICATION_JSON).build());

        } catch (Exception ex) {
            API.logger.catching(ex);
            return API.logger.exit(internalError());
        } finally {
            boolean error = false;

            try {
                if (seqReader != null) {
                    seqReader.close();
                }
            } catch (Exception ex) {
                API.logger.catching(ex);
                error = true;
            }

            try {
                if (locked) {
                    uploadDB.releaseLock(pt.toString());
                }
            } catch (Exception ex) {
                API.logger.catching(ex);
                error = true;
            }

            if (error) {
                return API.logger.exit(internalError());
            }
        }
    }

    @Path("fs/seq/ls/{path:.+}")
    @GET
    public Response seqLs(@PathParam("path") final String path,
            @HeaderParam("X-API-KEY") final String apiKey) {
        API.logger.entry(path);

        SequenceFile.Reader seqReader = null;

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_WRITE)) {
                return API.logger.exit(accessError());
            }

            logDB.insertLogEntry(path, "seqls", authDB.getName(apiKey));

            if (!Util.validatePath(path)) {
                return API.logger
                        .exit(clientError("Invalid path (contains illegal characters or too long)"));
            }

            JSONArray arr = new JSONArray();

            org.apache.hadoop.fs.Path pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix() + path);

            seqReader = new SequenceFile.Reader(
                    runtimeConfiguration.getFSConfiguration(),
                    SequenceFile.Reader.file(pt));

            BytesWritable key = new BytesWritable();

            while (seqReader.next(key)) {
                String keyAsStr = new String(key.getBytes(), 0, key.getLength());
                arr.put(keyAsStr);
            }

            return API.logger.exit(Response.ok(arr.toString(),
                    MediaType.APPLICATION_JSON).build());

        } catch (Exception ex) {
            API.logger.catching(ex);
            return API.logger.exit(internalError());
        } finally {
            if (seqReader != null) {
                try {
                    seqReader.close();
                } catch (Exception ex) {
                    API.logger.catching(ex);
                    return API.logger.exit(internalError());
                }
            }
        }
    }

    @Path("fs/seq/raw/{path:.+}")
    @GET
    @Produces({ MediaType.TEXT_PLAIN })
    public Response seqRaw(@PathParam("path") final String path,
            @QueryParam("fileName") final String fileName,
            @HeaderParam("X-API-KEY") final String apiKey) {
        API.logger.entry(path, fileName);

        SequenceFile.Reader seqReader = null;

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_READ)) {
                return API.logger.exit(accessError());
            }

            logDB.insertLogEntry(path, "seqraw", authDB.getName(apiKey));

            if (!Util.validatePath(path)) {
                return API.logger
                        .exit(clientError("Invalid path (contains illegal characters or too long)"));
            }

            org.apache.hadoop.fs.Path pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix() + path);

            seqReader = new SequenceFile.Reader(
                    runtimeConfiguration.getFSConfiguration(),
                    SequenceFile.Reader.file(pt));

            BytesWritable key = new BytesWritable();

            while (seqReader.next(key)) {
                String keyAsStr = new String(key.getBytes(), 0, key.getLength());
                if (keyAsStr.equals(fileName)) {
                    BytesWritable value = new BytesWritable();
                    seqReader.getCurrentValue(value);
                    seqReader.close();
                    return API.logger.exit(Response.ok(
                            new String(value.getBytes(), 0, value.getLength()),
                            MediaType.TEXT_PLAIN).build());
                }
            }

            return API.logger
                    .exit(generic404("File not found in sequence file!"));
        } catch (Exception ex) {
            API.logger.catching(ex);
            return API.logger.exit(internalError());
        } finally {
            if (seqReader != null) {
                try {
                    seqReader.close();
                } catch (Exception ex) {
                    API.logger.catching(ex);
                    return API.logger.exit(internalError());
                }
            }
        }

    }

    @Path("seq/up/{fileName}")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response seqUpload(@FormDataParam("meta") final String meta,
            @FormDataParam("data") final byte[] data,
            @PathParam("fileName") final String fileName,
            @HeaderParam("X-API-KEY") final String apiKey) {
        API.logger.entry(meta, data, fileName);

        SequenceFile.Writer seqWriter = null;
        org.apache.hadoop.fs.Path pt = null;
        String seq = null;
        boolean locked = false;

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_WRITE)) {
                return API.logger.exit(accessError());
            }

            logDB.insertLogEntry(fileName, "sequp", authDB.getName(apiKey));

            JSONObject obj = null;

            try {
                obj = new JSONObject(meta);
            } catch (Exception ex) {
                return API.logger.exit(clientError("Invalid JSON"));
            }

            if (obj.getString("msmntCampaign") == null
                    || obj.getString("format") == null
                    || obj.getString("seq") == null) {
                return API.logger
                        .exit(clientError("Invalid JSON. Need `msmntCampaign` and `format`."));
            }

            if (!Util.validatePathPart(obj.getString("msmntCampaign"))
                    || !Util.validatePathPart(obj.getString("format"))
                    || !Util.validatePathPart(obj.getString("seq"))) {
                return API.logger
                        .exit(clientError("Invalid `msmntCampaign`, `seq` or invalid `format` (contain illegal characters or too long)"));
            }

            if (!Util.validateFileName(fileName)) {
                return API.logger
                        .exit(clientError("Invalid file name (contains illegal characters or too long)"));
            }

            seq = obj.getString("seq");

            pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix()
                            + obj.getString("msmntCampaign") + "/"
                            + obj.getString("format") + "/" + seq + ".seq");

            locked = uploadDB.getLock(pt.toString());

            MessageDigest md = MessageDigest.getInstance("SHA-1");

            if (!uploadDB.insertSeqUpload(pt.toString(), meta, fileName,
                    authDB.getName(apiKey))) {
                return API.logger
                        .exit(clientError("Upload entry already exists!"));
            }

            md.update(data);

            String digest = Util.byteArr2HexStr(md.digest());

            seqWriter = SequenceFile.createWriter(
                    runtimeConfiguration.getFSConfiguration(),
                    SequenceFile.Writer.compression(CompressionType.RECORD),
                    SequenceFile.Writer.keyClass(BytesWritable.class),
                    SequenceFile.Writer.valueClass(BytesWritable.class),
                    SequenceFile.Writer.appendIfExists(true),
                    SequenceFile.Writer.file(pt));

            BytesWritable key = new BytesWritable(fileName.getBytes("UTF-8"));
            BytesWritable val = new BytesWritable(data);

            seqWriter.append(key, val);
            seqWriter.hflush();
            seqWriter.hsync();
            seqWriter.close();

            uploadDB.completeSeqUpload(pt.toString(), fileName, digest);

            return API.logger.exit(Response.ok(digest).build());
        } catch (JSONException ex) {
            API.logger.catching(ex);
            return API.logger.exit(clientError("Invalid JSON!"));
        } catch (Exception ex) {
            API.logger.catching(ex);
            uploadDB.insertError(pt.toString(), seq, ex.getMessage());
            return API.logger.exit(internalError());
        } finally {
            boolean error = false;

            if (seqWriter != null) {
                try {
                    seqWriter.close();
                } catch (Exception ex) {
                    API.logger.catching(ex);
                    uploadDB.insertError(pt.toString(), seq, ex.getMessage());
                    error = true;
                }
            }

            if (locked) {
                try {
                    uploadDB.releaseLock(pt.toString());
                } catch (Exception ex) {
                    API.logger.catching(ex);
                    uploadDB.insertError(pt.toString(), seq, ex.getMessage());
                    error = true;
                }
            }

            if (error) {
                API.logger.exit(internalError());
            }
        }
    }

    @Path("fs/size")
    @POST
    @Consumes({ MediaType.APPLICATION_OCTET_STREAM })
    public Response size(@HeaderParam("X-API-KEY") final String apiKey,
            final InputStream data) {
        API.logger.entry(data);

        try {
            uploadDB.getLock("foobar");

            byte chunk[] = new byte[runtimeConfiguration.getChunkSize()];
            long size = 0;
            int read;
            while ((read = data.read(chunk)) > 0) {
                size += read;
            }

            runtimeConfiguration.getFileSystem();

            uploadDB.releaseLock("foobar");

            return API.logger.exit(Response.ok("Size: " + Long.toString(size),
                    MediaType.TEXT_PLAIN).build());
        } catch (Exception ex) {
            API.logger.catching(ex);
            return API.logger.exit(internalError());
        }
    }

    @Path("status")
    @GET
    public Response status() {
        API.logger.entry();
        return API.logger.exit(Response.ok("RUNNING", MediaType.TEXT_PLAIN)
                .build());
    }

    @Path("up/{fileName}")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response upload(@FormDataParam("meta") final String meta,
            @FormDataParam("data") final InputStream data,
            @PathParam("fileName") final String fileName,
            @HeaderParam("X-API-KEY") final String apiKey) {
        API.logger.entry(meta, data);

        OutputStream os = null;
        org.apache.hadoop.fs.Path pt = null;
        boolean locked = false;

        try {
            if (!checkAccessLevel(apiKey, AccessLevels.ACCESS_FS_WRITE)) {
                return API.logger.exit(accessError());
            }

            logDB.insertLogEntry(fileName, "up", authDB.getName(apiKey));

            JSONObject obj = null;

            try {
                obj = new JSONObject(meta);
            } catch (Exception ex) {
                return API.logger.exit(clientError("Invalid JSON"));
            }

            if (obj.getString("msmntCampaign") == null
                    || obj.getString("format") == null) {
                return API.logger
                        .exit(clientError("Invalid JSON. Need `msmntCampaign` and `format`."));
            }

            if (!Util.validatePathPart(obj.getString("msmntCampaign"))
                    || !Util.validatePathPart(obj.getString("format"))) {
                return API.logger
                        .exit(clientError("Invalid `msmntCampaign` or invalid `format` (contain illegal characters or too long)"));
            }

            if (!Util.validateFileName(fileName)) {
                return API.logger
                        .exit(clientError("Invalid file name (contains illegal characters or too long)"));
            }

            pt = new org.apache.hadoop.fs.Path(
                    runtimeConfiguration.getPathPrefix()
                            + obj.getString("msmntCampaign") + "/"
                            + obj.getString("format") + "/" + fileName);

            FileSystem fs = runtimeConfiguration.getFileSystem();

            if (fs.exists(pt)) {
                return API.logger.exit(clientError("Path already exists."));
            }

            MessageDigest md = MessageDigest.getInstance("SHA-1");

            locked = uploadDB.getLock(pt.toString());

            if (!locked) {
                return API.logger.exit(clientError("File is busy!"));
            }

            if (!uploadDB.insertUpload(pt.toString(), meta,
                    authDB.getName(apiKey), fileName)) {
                return API.logger
                        .exit(clientError("Upload entry already exists!"));
            }

            os = fs.create(pt);

            byte chunk[] = new byte[runtimeConfiguration.getChunkSize()];
            int read;
            while ((read = data.read(chunk)) > 0) {
                md.update(chunk, 0, read);
                os.write(chunk, 0, read);
            }

            os.flush();
            os.close();

            String digest = Util.byteArr2HexStr(md.digest());

            uploadDB.completeUpload(pt.toString(), digest);

            return API.logger.exit(Response.ok(digest).build());

        } catch (JSONException ex) {
            API.logger.catching(ex);
            return API.logger.exit(clientError("Invalid JSON!"));
        } catch (Exception ex) {
            API.logger.catching(ex);
            uploadDB.insertError(pt.toString(), "", ex.getMessage());
            return API.logger.exit(internalError());
        } finally {

            boolean error = false;

            if (os != null) {
                try {
                    os.close();
                } catch (Exception ex) {
                    API.logger.catching(ex);
                    uploadDB.insertError(pt.toString(), "", ex.getMessage());
                    error = true;
                }
            }
            if (data != null) {
                try {
                    data.close();
                } catch (Exception ex) {
                    API.logger.catching(ex);
                    uploadDB.insertError(pt.toString(), "", ex.getMessage());
                    error = true;
                }
            }

            try {
                if (locked) {
                    uploadDB.releaseLock(pt.toString());
                }
            } catch (Exception ex) {
                API.logger.catching(ex);
                uploadDB.insertError(pt.toString(), "", ex.getMessage());
                error = true;
            }

            if (error) {
                return API.logger.exit(internalError());
            }
        }
    }
}