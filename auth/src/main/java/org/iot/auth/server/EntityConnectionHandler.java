/*
 * Copyright (c) 2016, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * IOTAUTH_COPYRIGHT_VERSION_1
 */

package org.iot.auth.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.sql.SQLException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import com.sun.tools.javac.util.Pair;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Fields.Field;
import org.iot.auth.AuthCrypto;
import org.iot.auth.AuthServer;
import org.iot.auth.db.*;
import org.iot.auth.io.Buffer;
import org.iot.auth.io.BufferedString;
import org.iot.auth.io.VariableLengthInt;
import org.iot.auth.message.*;
import org.iot.auth.util.ExceptionToString;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A handler class for connections from each entity that requests Auth service (e.g., session key requests)
 * @author Hokeun Kim
 */
public class EntityConnectionHandler extends Thread {
    public EntityConnectionHandler(AuthServer server, Socket entitySocket, long timeOut) {
        this.socket = entitySocket;
        this.server = server;
        this.timeOut = timeOut;
    }
    public void run() {
        try {
            Buffer authNonce = AuthCrypto.getRandomBytes(AuthHelloMessage.AUTH_NONCE_SIZE);
            sendAuthHello(authNonce);

            long waitStartedTime = new Date().getTime();

            while (!socket.isClosed()) {
                InputStream is = socket.getInputStream();
                int availableLength = is.available();
                if (availableLength > 0) {
                    byte[] buf = new byte[availableLength];
                    int length = is.read(buf);

                    logger.debug("Received bytes ({}): {}", length, Buffer.toHexString(buf, 0, length));

                    // Process session key request
                    handleSessionKeyReq(buf, authNonce);
                    close();
                    return;
                }

                long currentTime = new Date().getTime();
                long elapsedTime = currentTime - waitStartedTime;
                if (timeOut < elapsedTime) {
                    logger.info("Timed out at {}, elapsed: {}, started at {}", new Date(currentTime),
                            elapsedTime, new Date(waitStartedTime));
                    close();
                    return;
                }
            }
        }
        catch (Exception e) {
            logger.error("Exception occurred while handling Auth service!\n {}",
                    ExceptionToString.convertExceptionToStackTrace(e));
            close();
            return;
        }
        close();
        return;
    }

    private void sendAuthHello(Buffer authNonce) throws IOException {
        logger.debug("Sending AUTH_HELLO to entity at Port {} with auth nonce {}",
                socket.getRemoteSocketAddress(), authNonce.toHexString());
        //System.out.println(Buffer.toHexString(authNonce));

        AuthHelloMessage authHello = new AuthHelloMessage(server.getAuthID(), authNonce);

        OutputStream outStream = socket.getOutputStream();
        outStream.write(authHello.serialize().getRawBytes());
    }

    private void handleSessionKeyReq(byte[] bytes, Buffer authNonce) throws RuntimeException, IOException,
            ParseException, SQLException, ClassNotFoundException
    {
        Buffer buf = new Buffer(bytes);
        MessageType type = MessageType.fromByte(buf.getByte(0));

        VariableLengthInt valLenInt = buf.getVariableLengthInt(IoTSPMessage.MSG_TYPE_SIZE);
        // rest of this is payload
        Buffer payload = buf.slice(IoTSPMessage.MSG_TYPE_SIZE + valLenInt.getRawBytes().length);

        if (type == MessageType.SESSION_KEY_REQ_IN_PUB_ENC) {
            logger.info("Received session key request message encrypted with public key!");
            // parse signed data
            final int RSA_KEY_SIZE = 256; // 2048 bits
            Buffer encPayload = payload.slice(0, payload.length() - RSA_KEY_SIZE);
            logger.debug("Encrypted data ({}): {}", encPayload.length(), encPayload.toHexString());
            Buffer signature = payload.slice(payload.length() - RSA_KEY_SIZE);
            Buffer decPayload = server.getCrypto().privateDecrypt(encPayload);

            logger.debug("Decrypted data ({}): {}", decPayload.length(), decPayload.toHexString());
            SessionKeyReqMessage sessionKeyReqMessage = new SessionKeyReqMessage(type, decPayload);

            RegisteredEntity requestingEntity = server.getRegEntity(sessionKeyReqMessage.getEntityName());

            // checking signature
            try {
                if (!server.getCrypto().verifySignedData(encPayload, signature, requestingEntity.getPublicKey())) {
                    throw new RuntimeException("Entity signature verification failed!!");
                }
                else {
                    logger.debug("Entity signature is correct!");
                }
            }
            catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
                throw new RuntimeException("Entity signature verification failed!!");
            }

            Pair<List<SessionKey>, SymmetricKeyCryptoSpec> ret =
                    processSessionKeyReq(requestingEntity, sessionKeyReqMessage, authNonce);
            List<SessionKey> sessionKeyList = ret.fst;
            SymmetricKeyCryptoSpec sessionCryptoSpec = ret.snd;

            // generate distribution key
            // Assuming AES-CBC-128
            DistributionKey distributionKey =
                    new DistributionKey(AuthCrypto.getRandomBytes(requestingEntity.getDistCryptoSpec().getCipherKeySize()),
                            new Date().getTime() + requestingEntity.getDisKeyValidity());
            // update distribution key
            server.updateDistributionKey(requestingEntity.getName(), distributionKey);

            Buffer encryptedDistKey = server.getCrypto().publicEncrypt(distributionKey.serialize(),
                    requestingEntity.getPublicKey());
            encryptedDistKey.concat(server.getCrypto().signWithPrivateKey(encryptedDistKey));

            sendSessionKeyResp(distributionKey, requestingEntity.getDistCryptoSpec(), sessionKeyReqMessage.getEntityNonce(),
                    sessionKeyList, sessionCryptoSpec, encryptedDistKey);
            close();
        }
        else if (type == MessageType.SESSION_KEY_REQ) {
            logger.info("Received session key request message encrypted with distribution key!");
            BufferedString bufferedString = payload.getBufferedString(0);
            String requestingEntityName = bufferedString.getString();
            RegisteredEntity requestingEntity = server.getRegEntity(requestingEntityName);

            // TODO: check distribution key validity here and if not, refuse request
            if (requestingEntity.getDistributionKey() == null) {
                logger.info("No distribution key is available!");
                sendAuthAlert(AuthAlertCode.INVALID_DISTRIBUTION_KEY);
                close();
                return;
            }
            else if (new Date().getTime() > requestingEntity.getDistributionKey().getExpirationTime().getTime()) {
                logger.info("Distribution key is expired!");
                sendAuthAlert(AuthAlertCode.INVALID_DISTRIBUTION_KEY);
                close();
                return;
            }

            Buffer encPayload = payload.slice(bufferedString.length());

            Buffer decPayloadAndMAC = AuthCrypto.symmetricDecrypt(encPayload,
                    requestingEntity.getDistributionKey().getKeyVal(), requestingEntity.getDistCryptoSpec().getCipherAlgo());

            // Check MAC (message authentication code) value within dec payload
            int hashLength = AuthCrypto.getHashLength(requestingEntity.getDistCryptoSpec().getHashAlgo());
            Buffer decPayload = decPayloadAndMAC.slice(0, decPayloadAndMAC.length() - hashLength);
            Buffer receivedMAC = decPayloadAndMAC.slice(decPayloadAndMAC.length() - hashLength);
            Buffer computedMAC = AuthCrypto.hash(decPayload, requestingEntity.getDistCryptoSpec().getHashAlgo());

            if (!receivedMAC.equals(computedMAC)) {
                throw new RuntimeException("MAC of session key request is NOT correct!");
            }
            else {
                logger.debug("MAC is correct!");
            }

            SessionKeyReqMessage sessionKeyReqMessage = new SessionKeyReqMessage(type, decPayload);

            Pair<List<SessionKey>, SymmetricKeyCryptoSpec> ret =
                    processSessionKeyReq(requestingEntity, sessionKeyReqMessage, authNonce);
            List<SessionKey> sessionKeyList = ret.fst;
            SymmetricKeyCryptoSpec sessionCryptoSpec = ret.snd;

            sendSessionKeyResp(requestingEntity.getDistributionKey(), requestingEntity.getDistCryptoSpec(), sessionKeyReqMessage.getEntityNonce(),
                    sessionKeyList, sessionCryptoSpec, null);
            close();
        }
        else {
            logger.info("Received unrecognized message from the entity!");
            close();
        }
    }

    private void sendAuthAlert(AuthAlertCode authAlertCode) throws IOException {
        socket.getOutputStream().write(new AuthAlertMessage(authAlertCode).serialize().getRawBytes());
    }

    private void sendSessionKeyResp(DistributionKey distributionKey, SymmetricKeyCryptoSpec distCryptoSpec, Buffer entityNonce,
                                    List<SessionKey> sessionKeyList, SymmetricKeyCryptoSpec sessionCryptoSpec,
                                    Buffer encryptedDistKey) throws IOException
    {
        SessionKeyRespMessage sessionKeyResp = null;
        if (encryptedDistKey != null) {
            sessionKeyResp = new SessionKeyRespMessage(encryptedDistKey, entityNonce, sessionCryptoSpec, sessionKeyList);
        }
        else {
            sessionKeyResp = new SessionKeyRespMessage(entityNonce, sessionCryptoSpec, sessionKeyList);
        }
        socket.getOutputStream().write(sessionKeyResp.serializeAndEncrypt(distributionKey, distCryptoSpec).getRawBytes());
    }

    private Pair<List<SessionKey>, SymmetricKeyCryptoSpec> processSessionKeyReq(
            RegisteredEntity requestingEntity, SessionKeyReqMessage sessionKeyReqMessage, Buffer authNonce)
            throws IOException, ParseException, SQLException, ClassNotFoundException
    {
        logger.debug("Sender entity: {}", sessionKeyReqMessage.getEntityName());

        logger.debug("Received auth nonce: {}", sessionKeyReqMessage.getAuthNonce().toHexString());
        if (!authNonce.equals(sessionKeyReqMessage.getAuthNonce())) {
            throw new RuntimeException("Auth nonce does not match!");
        }
        else {
            logger.debug("Auth nonce is correct!");
        }

        JSONObject purpose = sessionKeyReqMessage.getPurpose();
        SessionKeyReqPurpose reqPurpose = new SessionKeyReqPurpose(purpose);

        SymmetricKeyCryptoSpec cryptoSpec = null;
        List<SessionKey> sessionKeyList = null;
        switch (reqPurpose.getTargetType()) {
            case TARGET_GROUP:
            case PUBLISH_TOPIC:
            case SUBSCRIBE_TOPIC: {
                CommunicationPolicy communicationPolicy = server.getCommPolicy(requestingEntity.getGroup(),
                        reqPurpose.getTargetType(), (String)reqPurpose.getTarget());
                if (communicationPolicy == null) {
                    throw new RuntimeException("Unrecognized Purpose: "
                            + purpose.toString());
                }
                cryptoSpec = communicationPolicy.getCryptoSpec();
                // generate session keys
                logger.debug("numKeys {}", sessionKeyReqMessage.getNumKeys());
                sessionKeyList = server.generateSessionKeys(requestingEntity.getName(),
                        sessionKeyReqMessage.getNumKeys(), communicationPolicy);
                break;
            }
            case SESSION_KEY_ID: {
                Object objTarget = reqPurpose.getTarget();
                SessionKey sessionKey = null;
                logger.debug("objTarget class: {}", objTarget.getClass());
                long sessionKeyID = -1;
                if (objTarget.getClass() == Integer.class) {
                    sessionKeyID = (long)(Integer)objTarget;
                }
                else if (objTarget.getClass() == Long.class) {
                    sessionKeyID = (Long)objTarget;
                }
                else {
                    throw new RuntimeException("Wrong class for session key ID!");
                }
                int authID = AuthDB.decodeAuthIDFromSessionKeyID(sessionKeyID);
                logger.info("ID of Auth that generated this key: {}", authID);

                if (authID == server.getAuthID()) {
                    logger.info("This session key was generated by me");
                    sessionKey = server.getSessionKeyByID(sessionKeyID);

                    sessionKeyList = new LinkedList<SessionKey>();
                    sessionKeyList.add(sessionKey);
                    cryptoSpec = sessionKey.getCryptoSpec();
                    server.addSessionKeyOwner(sessionKeyID, requestingEntity.getName());
                }
                else {
                    // TODO: if authID is not my ID, then send request via HTTPS
                    logger.info("This session key was generated by someone else");
                    return sendAuthSessionKeyReq(requestingEntity, authID, sessionKeyID);
                }

                break;
            }
            default: {
                logger.error("Unrecognized target for session key request!");
                break;
            }
        }

        return new Pair<>(sessionKeyList, cryptoSpec);
    }

    private Pair<List<SessionKey>, SymmetricKeyCryptoSpec> sendAuthSessionKeyReq(
            RegisteredEntity requestingEntity, int authID, long sessionKeyID) throws IOException, ParseException
    {
        logger.info("Sending auth session key req to Auth {}", authID);
        TrustedAuth trustedAuth = server.getTrustedAuthInfo(authID);

        Field name = new Fields.Field("Name", "Robert");
        Field age = new Field("Age", "32");
        Fields fields = new Fields();
        fields.put(name);
        fields.put(age);

        ContentResponse res = null;
        try {
            AuthSessionKeyReqMessage authSessionKeyReqMessage = new AuthSessionKeyReqMessage(sessionKeyID,
                    requestingEntity.getName(), requestingEntity.getGroup());
            res = server.performPostRequest(
                    "https://" + trustedAuth.getHost() + ":" + trustedAuth.getPort(),
                    fields,
                    authSessionKeyReqMessage.toJSONObject());
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.error("Exception {}", ExceptionToString.convertExceptionToStackTrace(e));
            throw new RuntimeException();
        }

        logger.info("Received contents via https {}", res.getContentAsString());

        AuthSessionKeyRespMessage authSessionKeyRespMessage = AuthSessionKeyRespMessage.fromJSONObject(
                (JSONObject) new JSONParser().parse(res.getContentAsString()));

        logger.info("Received AuthSessionKeyRespMessage: {}", authSessionKeyRespMessage.toString());
        List<SessionKey> sessionKeyList = new LinkedList<SessionKey>();
        SessionKey sessionKey = authSessionKeyRespMessage.getSessionKey();
        sessionKeyList.add(sessionKey);
        return new Pair<>(sessionKeyList, sessionKey.getCryptoSpec());
    }

    private void close() {
        try {
            if (!socket.isClosed()) {
                logger.info("Closing connection with socket at {}", socket.getRemoteSocketAddress());
                socket.close();
            }
        }
        catch (IOException e) {
            logger.error("Exception occurred while closing socket!\n {}",
                    ExceptionToString.convertExceptionToStackTrace(e));
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(EntityConnectionHandler.class);
    private Socket socket;
    private AuthServer server;
    private long timeOut;
}
