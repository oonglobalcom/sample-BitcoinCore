/**
 * Copyright 2011 Google Inc.
 * Copyright 2013-2014 Ronald W Hoffman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ScripterRon.BitcoinCore;

import org.bouncycastle.asn1.x9.X9ECParameters;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;

import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve;

import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECFieldElement;
import org.bouncycastle.math.ec.ECPoint;

import org.bouncycastle.util.encoders.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.math.BigInteger;

import java.security.SecureRandom;

import java.util.Arrays;

/**
 * ECKey supports elliptic curve cryptographic operations using a public/private
 * key pair.  A private key is required to create a signature and a public key is
 * required to verify a signature.  The private key is always encrypted using AES
 * when it is serialized for storage on external media.
 */
public class ECKey {

    /** Half-curve order for generating canonical S */
    public static final BigInteger HALF_CURVE_ORDER;

    /** Elliptic curve parameters (secp256k1 curve) */
    private static final ECDomainParameters ecParams;
    static {
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        ecParams = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
        HALF_CURVE_ORDER = params.getN().shiftRight(1);
    }

    /** Strong random number generator */
    private static final SecureRandom secureRandom = new SecureRandom();

    /** Signed message header */
    private static final String BITCOIN_SIGNED_MESSAGE_HEADER = "Bitcoin Signed Message:\n";

    /** Key label */
    private String label = "";

    /** Public key */
    private byte[] pubKey;

    /** Public key hash */
    private byte[] pubKeyHash;

    /** Private key */
    private BigInteger privKey;

    /** Key creation time (seconds) */
    private long creationTime;

    /** Compressed public key */
    private boolean isCompressed;

    /** Change key */
    private boolean isChange;

    /**
     * Creates an ECKey with a new public/private key pair.  Point compression is used
     * so the resulting public key will be 33 bytes (32 bytes for the x-coordinate and
     * 1 byte to represent the y-coordinate sign)
     */
    public ECKey() {
        ECKeyPairGenerator keyGenerator = new ECKeyPairGenerator();
        ECKeyGenerationParameters keyGenParams = new ECKeyGenerationParameters(ecParams, secureRandom);
        keyGenerator.init(keyGenParams);
        AsymmetricCipherKeyPair keyPair = keyGenerator.generateKeyPair();
        ECPrivateKeyParameters privKeyParams = (ECPrivateKeyParameters)keyPair.getPrivate();
        ECPublicKeyParameters pubKeyParams = (ECPublicKeyParameters)keyPair.getPublic();
        privKey = privKeyParams.getD();
        pubKey = pubKeyParams.getQ().getEncoded(true);
        creationTime = System.currentTimeMillis()/1000;
        isCompressed = true;
    }

    /**
     * Creates an ECKey with just a public key
     *
     * @param       pubKey              Public key
     */
    public ECKey(byte[] pubKey) {
        this(pubKey, null, false);
    }

    /**
     * Creates an ECKey with the supplied public/private key pair.  The private key may be
     * null if you only want to use this ECKey to verify signatures.  The public key will
     * be generated from the private key if it is not provided (the 'compressed' parameter
     * determines the type of public key created)
     *
     * @param       pubKey              Public key or null
     * @param       privKey             Private key or null
     * @param       compressed          TRUE to create a compressed public key
     */
    public ECKey(byte[] pubKey, BigInteger privKey, boolean compressed) {
        this.privKey = privKey;
        if (pubKey != null) {
            this.pubKey = pubKey;
            isCompressed = (pubKey.length==33);
        } else if (privKey != null) {
            this.pubKey = pubKeyFromPrivKey(privKey, compressed);
            isCompressed = compressed;
        } else {
            throw new IllegalArgumentException("You must provide at least a private key or a public key");
        }
        creationTime = System.currentTimeMillis()/1000;
    }

    /**
     * Returns the key creation time
     *
     * @return      Key creation time (seconds)
     */
    public long getCreationTime() {
        return creationTime;
    }

    /**
     * Sets the key creation time
     *
     * @param       creationTime        Key creation time (seconds)
     */
    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    /**
     * Returns the key label
     *
     * @return      Key label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Sets the key label
     *
     * @param       label               Key label
     */
    public void setLabel(String label) {
        this.label = label;
    }

    /**
     * Checks if this is a change key
     *
     * @return                          TRUE if this is a change key
     */
    public boolean isChange() {
        return isChange;
    }

    /**
     * Sets change key status
     *
     * @param       isChange            TRUE if this is a change key
     */
    public void setChange(boolean isChange) {
        this.isChange = isChange;
    }

    /**
     * Returns the public key (as used in transaction scriptSigs).  A compressed
     * public key is 33 bytes and starts with '02' or '03' while an uncompressed
     * public key is 65 bytes and starts with '04'.
     *
     * @return                          Public key
     */
    public byte[] getPubKey() {
        return pubKey;
    }

    /**
     * Returns the public key hash as used in addresses.  The hash is 20 bytes.
     *
     * @return                          Public key hash
     */
    public byte[] getPubKeyHash() {
        if (pubKeyHash == null)
            pubKeyHash = Utils.sha256Hash160(pubKey);
        return pubKeyHash;
    }

    /**
     * Returns the address for this public key
     *
     * @return                          Address
     */
    public Address toAddress() {
        return new Address(getPubKeyHash(), label);
    }

    /**
     * Returns the private key
     *
     * @return                          Private key or null if there is no private key
     */
    public BigInteger getPrivKey() {
        return privKey;
    }

    /**
     * Checks if there is a private key
     *
     * @return                          TRUE if there is a private key
     */
    public boolean hasPrivKey() {
        return (privKey!=null);
    }

    /**
     * Returns the encoded private key in the format used by the Bitcoin reference client
     *
     * @return                              Dumped private key
     */
    public DumpedPrivateKey getPrivKeyEncoded() {
        if (privKey == null)
            throw new IllegalStateException("No private key available");
        return new DumpedPrivateKey(privKey, isCompressed);
    }

    /**
     * Checks if the public key is compressed
     *
     * @return                          TRUE if the public key is compressed
     */
    public boolean isCompressed() {
        return isCompressed;
    }

    /**
     * Creates a signature for the supplied contents using the private key
     *
     * @param       contents                Contents to be signed
     * @return                              ECDSA signature
     * @throws      ECException             Unable to create signature
     */
    public ECDSASignature createSignature(byte[] contents) throws ECException {
        if (privKey == null)
            throw new IllegalStateException("No private key available");
        //
        // Get the double SHA-256 hash of the signed contents
        //
        byte[] contentsHash = Utils.doubleDigest(contents);
        //
        // Create the signature
        //
        BigInteger[] sigs;
        try {
            ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
            ECPrivateKeyParameters privKeyParams = new ECPrivateKeyParameters(privKey, ecParams);
            signer.init(true, privKeyParams);
            sigs = signer.generateSignature(contentsHash);
        } catch (RuntimeException exc) {
            throw new ECException("Exception while creating signature", exc);
        }
        //
        // Create a canonical signature by adjusting the S component to be less than or equal to
        // half the curve order.
        //
        if (sigs[1].compareTo(HALF_CURVE_ORDER) > 0)
            sigs[1] = ecParams.getN().subtract(sigs[1]);
        return new ECDSASignature(sigs[0], sigs[1]);
    }

    /**
     * Verifies a signature for the signed contents using the public key
     *
     * @param       contents            The signed contents
     * @param       signature           DER-encoded signature
     * @return                          TRUE if the signature if valid, FALSE otherwise
     * @throws      ECException         Unable to verify the signature
     */
    public boolean verifySignature(byte[] contents, byte[] signature) throws ECException {
        boolean isValid = false;
        //
        // Decode the DER-encoded signature and get the R and S values
        //
        ECDSASignature sig = new ECDSASignature(signature);
        //
        // Get the double SHA-256 hash of the signed contents
        //
        byte[] contentsHash = Utils.doubleDigest(contents);
        //
        // Verify the signature
        //
        try {
            ECDSASigner signer = new ECDSASigner();
            ECPublicKeyParameters params = new ECPublicKeyParameters(
                                                ecParams.getCurve().decodePoint(pubKey), ecParams);
            signer.init(false, params);
            isValid = signer.verifySignature(contentsHash, sig.getR(), sig.getS());
        } catch (RuntimeException exc) {
            throw new ECException("Runtime exception while verifying signature", exc);
        }
        return isValid;
    }

    /**
     * Signs a message using the private key
     *
     * @param       message             Message to be signed
     * @return                          Base64-encoded signature string
     * @throws      ECException         Unable to sign the message
     */
    public String signMessage(String message) throws ECException {
        String encodedSignature;
        if (privKey == null)
            throw new IllegalStateException("No private key available");
        try {
            //
            // Format the message for signing
            //
            byte[] contents;
            try (ByteArrayOutputStream outStream = new ByteArrayOutputStream(message.length()*2)) {
                byte[] headerBytes = BITCOIN_SIGNED_MESSAGE_HEADER.getBytes("UTF-8");
                outStream.write(VarInt.encode(headerBytes.length));
                outStream.write(headerBytes);
                byte[] messageBytes = message.getBytes("UTF-8");
                outStream.write(VarInt.encode(messageBytes.length));
                outStream.write(messageBytes);
                contents = outStream.toByteArray();
            }
            //
            // Create the signature
            //
            ECDSASignature sig = createSignature(contents);
            //
            // Get the RecID used to recover the public key from the signature
            //
            BigInteger e = new BigInteger(1, Utils.doubleDigest(contents));
            int recID = -1;
            for (int i=0; i<4; i++) {
                ECKey k = recoverFromSignature(i, sig, e, isCompressed());
                if (k != null && Arrays.equals(k.getPubKey(), pubKey)) {
                    recID = i;
                    break;
                }
            }
            if (recID == -1)
                throw new ECException("Unable to recover public key from signature");
            //
            // The message signature consists of a header byte followed by the R and S values
            //
            int headerByte = recID + 27 + (isCompressed() ? 4 : 0);
            byte[] sigData = new byte[65];
            sigData[0] = (byte)headerByte;
            System.arraycopy(Utils.bigIntegerToBytes(sig.getR(), 32), 0, sigData, 1, 32);
            System.arraycopy(Utils.bigIntegerToBytes(sig.getS(), 32), 0, sigData, 33, 32);
            //
            // Create a Base-64 encoded string for the message signature
            //
            encodedSignature = new String(Base64.encode(sigData), "UTF-8");
        } catch (IOException exc) {
            throw new IllegalStateException("Unexpected IOException", exc);
        }
        return encodedSignature;
    }

    /**
     * Verifies a message signature using the signing address
     *
     * @param       address             The address that signed the message
     * @param       message             The message that was signed
     * @param       encodedSignature    The Base64-encoded signature
     * @return                          TRUE if the signature is valid for this key
     * @throws      SignatureException  Signature is not valid
     */
    public static boolean verifyMessage(String address, String message, String encodedSignature)
                                        throws SignatureException {
        //
        // Decode the Base64-encoded signature
        //
        byte[] decodedSignature;
        try {
            decodedSignature = Base64.decode(encodedSignature);
        } catch (RuntimeException exc) {
            throw new SignatureException("Unable to decode the signature", exc);
        }
        //
        // Get the selector, R and S values
        //
        // The selector byte has the following values:
        //   0x1B = First key, even Y
        //   0x1C = First key, odd Y
        //   0x1D = Second key, even Y
        //   0x1E = Second key, odd Y
        //
        // If the public key was compressed, 4 is added to the selector value
        //
        if (decodedSignature.length < 65)
            throw new SignatureException("Signature is too short");
        int headerByte = (int)decodedSignature[0]&0xff;
        if (headerByte < 27 || headerByte > 34)
            throw new SignatureException(String.format("Header byte %d is out of range", headerByte));
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(decodedSignature, 1, 33));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(decodedSignature, 33, 65));
        ECDSASignature sig = new ECDSASignature(r, s);
        boolean compressed = false;
        if (headerByte >= 31) {
            compressed = true;
            headerByte -= 4;
        }
        int recID = headerByte - 27;
        ECKey key;
        byte[] contents;
        //
        // Format the message for signing
        //
        try {
            try (ByteArrayOutputStream outStream = new ByteArrayOutputStream(message.length()*2)) {
                byte[] headerBytes = BITCOIN_SIGNED_MESSAGE_HEADER.getBytes("UTF-8");
                outStream.write(VarInt.encode(headerBytes.length));
                outStream.write(headerBytes);
                byte[] messageBytes = message.getBytes("UTF-8");
                outStream.write(VarInt.encode(messageBytes.length));
                outStream.write(messageBytes);
                contents = outStream.toByteArray();
            }
            BigInteger e = new BigInteger(1, Utils.doubleDigest(contents));
            //
            // Get the public key from the signature
            //
            key = recoverFromSignature(recID, sig, e, compressed);
            if (key == null)
                throw new SignatureException("Unable to recover public key from signature");
        } catch (IOException exc) {
            throw new IllegalStateException("Unexpected IOException", exc);
        } catch (IllegalArgumentException exc) {
            throw new SignatureException("Signature is not valid");
        }
        //
        // The signature is correct if the recovered public key hash matches the supplied hash
        //
        return key.toAddress().toString().equals(address);
    }

    /**
     * <p>Given the components of a signature and a selector value, recover and return the public key
     * that generated the signature according to the algorithm in SEC1v2 section 4.1.6.</p>
     *
     * <p>The recID is an index from 0 to 3 which indicates which of the 4 possible keys is the correct one.
     * Because the key recovery operation yields multiple potential keys, the correct key must either be
     * stored alongside the signature, or you must be willing to try each recId in turn until you find one
     * that outputs the key you are expecting.</p>
     *
     * <p>If this method returns null, it means recovery was not possible and recID should be iterated.</p>
     *
     * <p>Given the above two points, a correct usage of this method is inside a for loop from 0 to 3, and if the
     * output is null OR a key that is not the one you expect, you try again with the next recID.</p>
     *
     * @param       recID               Which possible key to recover.
     * @param       sig                 R and S components of the signature
     * @param       e                   The double SHA-256 hash of the original message
     * @param       compressed          Whether or not the original public key was compressed
     * @return      An ECKey containing only the public part, or null if recovery wasn't possible
     */
    private static ECKey recoverFromSignature(int recID, ECDSASignature sig, BigInteger e, boolean compressed) {
        BigInteger n = ecParams.getN();
        BigInteger i = BigInteger.valueOf((long)recID / 2);
        BigInteger x = sig.getR().add(i.multiply(n));
        //
        //   Convert the integer x to an octet string X of length mlen using the conversion routine
        //        specified in Section 2.3.7, where mlen = ⌈(log2 p)/8⌉ or mlen = ⌈m/8⌉.
        //   Convert the octet string (16 set binary digits)||X to an elliptic curve point R using the
        //        conversion routine specified in Section 2.3.4. If this conversion routine outputs 'invalid', then
        //        do another iteration.
        //
        // More concisely, what these points mean is to use X as a compressed public key.
        //
        SecP256K1Curve curve = (SecP256K1Curve)ecParams.getCurve();
        BigInteger prime = curve.getQ();
        if (x.compareTo(prime) >= 0) {
            return null;
        }
        //
        // Compressed keys require you to know an extra bit of data about the y-coordinate as
        // there are two possibilities.  So it's encoded in the recID.
        //
        ECPoint R = decompressKey(x, (recID & 1) == 1);
        if (!R.multiply(n).isInfinity())
            return null;
        //
        //   For k from 1 to 2 do the following.   (loop is outside this function via iterating recId)
        //     Compute a candidate public key as:
        //       Q = mi(r) * (sR - eG)
        //
        // Where mi(x) is the modular multiplicative inverse. We transform this into the following:
        //               Q = (mi(r) * s ** R) + (mi(r) * -e ** G)
        // Where -e is the modular additive inverse of e, that is z such that z + e = 0 (mod n).
        // In the above equation, ** is point multiplication and + is point addition (the EC group operator).
        //
        // We can find the additive inverse by subtracting e from zero then taking the mod. For example the additive
        // inverse of 3 modulo 11 is 8 because 3 + 8 mod 11 = 0, and -3 mod 11 = 8.
        //
        BigInteger eInv = BigInteger.ZERO.subtract(e).mod(n);
        BigInteger rInv = sig.getR().modInverse(n);
        BigInteger srInv = rInv.multiply(sig.getS()).mod(n);
        BigInteger eInvrInv = rInv.multiply(eInv).mod(n);
        ECPoint q = ECAlgorithms.sumOfTwoMultiplies(ecParams.getG(), eInvrInv, R, srInv);
        return new ECKey(q.getEncoded(compressed));
    }

    /**
     * Decompress a compressed public key (x coordinate and low-bit of y-coordinate).
     *
     * @param       xBN                 X-coordinate
     * @param       yBit                Sign of Y-coordinate
     * @return                          Uncompressed public key
     */
    private static ECPoint decompressKey(BigInteger xBN, boolean yBit) {
        SecP256K1Curve curve = (SecP256K1Curve)ecParams.getCurve();
        ECFieldElement x = curve.fromBigInteger(xBN);
        ECFieldElement alpha = x.multiply(x.square().add(curve.getA())).add(curve.getB());
        ECFieldElement beta = alpha.sqrt();
        if (beta == null)
            throw new IllegalArgumentException("Invalid point compression");
        ECPoint ecPoint;
        BigInteger nBeta = beta.toBigInteger();
        if (nBeta.testBit(0) == yBit) {
            ecPoint = curve.createPoint(x.toBigInteger(), nBeta);
        } else {
            ECFieldElement y = curve.fromBigInteger(curve.getQ().subtract(nBeta));
            ecPoint = curve.createPoint(x.toBigInteger(), y.toBigInteger());
        }
        return ecPoint;
    }

    /**
     * Create the public key from the private key
     *
     * @param       privKey             Private key
     * @param       compressed          TRUE to generate a compressed public key
     * @return                          Public key
     */
    private byte[] pubKeyFromPrivKey(BigInteger privKey, boolean compressed) {
        ECPoint point = ecParams.getG().multiply(privKey);
        return point.getEncoded(compressed);
    }

    /**
     * Checks if two objects are equal
     *
     * @param       obj             The object to check
     * @return                      TRUE if the object is equal
     */
    @Override
    public boolean equals(Object obj) {
        boolean areEqual = false;
        if (obj != null && (obj instanceof ECKey))
            areEqual = Arrays.equals(pubKey, ((ECKey)obj).pubKey);
        return areEqual;
    }

    /**
     * Returns the hash code for this object
     *
     * @return                      Hash code
     */
    @Override
    public int hashCode() {
        return ((int)pubKey[0]&0xff) | (((int)pubKey[1]&0xff)<<8) |
                                       (((int)pubKey[2]&0xff)<<16) | (((int)pubKey[3]&0xff)<<24);
    }
}