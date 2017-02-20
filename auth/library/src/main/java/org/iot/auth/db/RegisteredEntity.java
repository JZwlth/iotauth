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

package org.iot.auth.db;

import org.bouncycastle.jce.provider.JCERSAPublicKey;
import org.iot.auth.crypto.SymmetricKeyCryptoSpec;
import org.iot.auth.db.bean.RegisteredEntityTable;
import org.iot.auth.io.Buffer;
import org.iot.auth.io.BufferedString;
import org.iot.auth.io.VariableLengthInt;
import org.iot.auth.util.DateHelper;
import org.json.simple.JSONObject;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/**
 * A class for a registered entity instance.
 * @author Hokeun Kim
 */
public class RegisteredEntity {
    private String name;
    private String group;
    private String distProtocol;
    private boolean usePermanentDistKey;
    private PublicKey publicKey;
    private String publicKeyCryptoSpec;
    private long distKeyValidityPeriod;
    private int maxSessionKeysPerRequest;
    private SymmetricKeyCryptoSpec distCryptoSpec;
    private boolean active;
    private int backupToAuthID;
    private int backupFromAuthID;
    private DistributionKey distributionKey = null;

    public RegisteredEntity(RegisteredEntityTable tableElement, DistributionKey distributionKey)
    {
        this.name = tableElement.getName();
        this.group = tableElement.getGroup();
        this.distProtocol = tableElement.getDistProtocol();
        this.usePermanentDistKey = tableElement.getUsePermanentDistKey();
        this.maxSessionKeysPerRequest = tableElement.getMaxSessionKeysPerRequest();
        this.publicKey = tableElement.getPublicKey();
        this.publicKeyCryptoSpec = tableElement.getPublicKeyCryptoSpec();
        this.distKeyValidityPeriod = DateHelper.parseTimePeriod(tableElement.getDistKeyValidityPeriod());
        this.distCryptoSpec = SymmetricKeyCryptoSpec.fromSpecString(tableElement.getDistCryptoSpec());
        this.active = tableElement.isActive();
        this.backupToAuthID = tableElement.getBackupToAuthID();
        this.backupFromAuthID = tableElement.getBackupFromAuthID();
        this.distributionKey = distributionKey; // Decrypted from database
    }
    public String getName() {
        return name;
    }
    public String getGroup() {
        return group;
    }
    public String getDistProtocol() {
        return distProtocol;
    }
    public int getMaxSessionKeysPerRequest() {
        return maxSessionKeysPerRequest;
    }
    public PublicKey getPublicKey() {
        return publicKey;
    }
    public long getDistKeyValidityPeriod() {
        return distKeyValidityPeriod;
    }
    public DistributionKey getDistributionKey() {
        return distributionKey;
    }
    public SymmetricKeyCryptoSpec getDistCryptoSpec() {
        return distCryptoSpec;
    }
    public String getPublicKeyCryptoSpec() {
        return publicKeyCryptoSpec;
    }

    public boolean isActive() {
        return active;
    }

    public int getBackupToAuthID() {
        return backupToAuthID;
    }

    public int getBackupFromAuthID() {
        return backupFromAuthID;
    }

    public String toString() {
        String ret = "Name: " + name + "\tGroup: " + group +
                "\tDistProtocol: " + distProtocol +
                "\tUsePermanentKey: " + usePermanentDistKey +
                "\tDistKeyValidityPeriod: " + distKeyValidityPeriod +
                "\tDistCryptoSpec: " + distCryptoSpec.toString() +
                "\tActive: " + active +
                "\tBackupToAuthID: " + backupToAuthID +
                "\tBackupFromAuthID: " + backupFromAuthID;

        ret += "\tDistKey: ";
        if (distributionKey == null) {
            ret += "NULL";
        }
        else {
            ret += distributionKey.toString();
        }
        if (!usePermanentDistKey) {
            ret += "\tPublicKeyCryptoSpec: " + publicKeyCryptoSpec;
            ret += "\tPublicKey: " + Buffer.toHexString(publicKey.getEncoded());
        }
        return ret;
    }
    public void setDistributionKey(DistributionKey distributionKey) {
        this.distributionKey = distributionKey;
    }

    private int REG_ENTITY_INT_SIZE = 4;

    // TODO: record the buffer length when concatnating this
    public Buffer serialize() {
        // UsePermanentDistKey | Active -> Byte
        // MaxSessionKeysPerRequest -> INT
        // BackupToAuthID -> INT
        // BackupFromAuthID -> INT
        // DistKeyValidityPeriod -> LONG
        Buffer buffer = new Buffer(Buffer.BYTE_SIZE + 3 * Buffer.INT_SIZE + Buffer.LONG_SIZE);
        byte usePermanentDistKeyActive = 0;
        usePermanentDistKeyActive += (usePermanentDistKey ? 2 : 0);
        usePermanentDistKeyActive += (active ? 1 : 0);
        int curIndex = 0;

        buffer.putByte(usePermanentDistKeyActive, curIndex);
        curIndex += Buffer.BYTE_SIZE;
        buffer.putInt(maxSessionKeysPerRequest, curIndex);
        curIndex += Buffer.INT_SIZE;
        buffer.putInt(backupToAuthID, curIndex);
        curIndex += Buffer.INT_SIZE;
        buffer.putInt(backupFromAuthID, curIndex);
        curIndex += Buffer.INT_SIZE;
        buffer.putLong(distKeyValidityPeriod, curIndex);
        curIndex += Buffer.LONG_SIZE;

        // String data
        buffer.concat(new BufferedString(name).serialize());
        buffer.concat(new BufferedString(group).serialize());
        buffer.concat(new BufferedString(distProtocol).serialize());
        if (publicKeyCryptoSpec == null) {
            buffer.concat(new BufferedString("").serialize());
        }
        else {
            buffer.concat(new BufferedString(publicKeyCryptoSpec).serialize());
        }
        buffer.concat(new BufferedString(distCryptoSpec.toSpecString()).serialize());

        Buffer keyBuffer;
        if (usePermanentDistKey) {
            keyBuffer = distributionKey.serialize();
        }
        else {
            keyBuffer = new Buffer(publicKey.getEncoded());
        }
        buffer.concat(new VariableLengthInt(keyBuffer.length()).serialize());
        buffer.concat(keyBuffer);
        return buffer;
    }

    public RegisteredEntity(Buffer buffer) throws NoSuchAlgorithmException, InvalidKeySpecException {
        int curIndex = 0;

        byte usePermanentDistKeyActive = buffer.getByte(curIndex);
        curIndex += Buffer.BYTE_SIZE;

        this.usePermanentDistKey = (usePermanentDistKeyActive & 2) != 0;
        this.active = (usePermanentDistKeyActive & 1) != 0;

        this.maxSessionKeysPerRequest = buffer.getInt(curIndex);
        curIndex += Buffer.INT_SIZE;
        this.backupToAuthID  = buffer.getInt(curIndex);
        curIndex += Buffer.INT_SIZE;
        this.backupFromAuthID  = buffer.getInt(curIndex);
        curIndex += Buffer.INT_SIZE;
        this.distKeyValidityPeriod  = buffer.getLong(curIndex);
        curIndex += Buffer.LONG_SIZE;

        BufferedString bufString;
        bufString = buffer.getBufferedString(curIndex);
        curIndex += bufString.length();
        this.name = bufString.getString();
        bufString = buffer.getBufferedString(curIndex);
        curIndex += bufString.length();
        this.group = bufString.getString();
        bufString = buffer.getBufferedString(curIndex);
        curIndex += bufString.length();
        this.distProtocol = bufString.getString();
        bufString = buffer.getBufferedString(curIndex);
        curIndex += bufString.length();
        this.publicKeyCryptoSpec = bufString.getString();
        if (this.publicKeyCryptoSpec.length() == 0) {
            this.publicKeyCryptoSpec = null;
        }
        bufString = buffer.getBufferedString(curIndex);
        curIndex += bufString.length();
        this.distCryptoSpec = SymmetricKeyCryptoSpec.fromSpecString(bufString.getString());

        VariableLengthInt varLenInt = buffer.getVariableLengthInt(curIndex);
        curIndex += varLenInt.getRawBytes().length;
        int keyBufferLength = varLenInt.getNum();

        Buffer keyBuffer = buffer.slice(curIndex, curIndex + keyBufferLength);
        curIndex += keyBufferLength;

        if (usePermanentDistKey) {
            // parse this to distribution key
            this.distributionKey = DistributionKey.fromBuffer(this.distCryptoSpec, keyBuffer);
            //keyBuffer
        }
        else {
            // decode public key
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            X509EncodedKeySpec pubSpec = new X509EncodedKeySpec(keyBuffer.getRawBytes());
            publicKey = keyFactory.generatePublic(pubSpec);
        }
    }
}
