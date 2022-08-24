package us.q3q.fido2;

import javacard.framework.*;
import javacard.security.*;
import javacardx.apdu.ExtendedLength;

import javacardx.crypto.Cipher;

/**
 * Core applet class implementing FIDO2 specifications on Javacard
 */
public class FIDO2Applet extends Applet implements ExtendedLength {

    // Configurable parameters
    /**
     * If true, permit the creation of resident keys without a PIN being set or provided.
     * This is "normal" for a FIDO2 authenticator, but means keys on the device could be
     * accessed in the event of a software bug or hardware fault.
     */
    private static final boolean ALLOW_RESIDENT_KEY_CREATION_WITHOUT_PIN = false;
    /**
     * If true, the authenticator will refuse to reset itself until the following three steps happen in order:
     *
     * 1. a reset command is sent
     * 2. the authenticator is entirely powered off
     * 3. another reset command is sent
     *
     * This is to guard against accidental or malware-driven authenticator resets. It doesn't comply with the FIDO
     * standards. The first reset will respond with OPERATION_DENIED, and if any credential-manipulation commands
     * are received between steps one and three, the process must be started over for the reset to be effective.
     *
     * Note that you still don't need the PIN in order to reset the authenticator: that would defeat the purpose of
     * the full reset...
     */
    private static final boolean PROTECT_AGAINST_MALICIOUS_RESETS = false;
    /**
     * If true, *really* minimize RAM usage. This will lead to extensive flash wear compared to the default...
     * all objects and buffers that can be put in flash, ARE put in flash.
     */
    private static final boolean MINIMIZE_RAM_USE = false;
    /**
     * Size of buffer used for receiving incoming data and sending responses.
     * To be standards-compliant, must be at least 1024 bytes, but can be larger.
     */
    private static final short BUFFER_MEM_SIZE = 1024;
    /**
     * Amount of "scratch" working memory
     */
    private static final short SCRATCH_SIZE = 304;
    /**
     * Number of resident key slots - how many credentials can this authenticator store?
     */
    private static final short NUM_RESIDENT_KEY_SLOTS = 50;
    /**
     * How long an RP identifier is allowed to be for a resident key. Values longer than this are truncated.
     * The CTAP2.1 standard says the minimum value for this is 32.
     */
    private static final short MAX_RESIDENT_RP_ID_LENGTH = 32;
    /**
     * How long an RP's user identifier is allowed to be - affects storage used by resident keys.
     */
    private static final short MAX_USER_ID_LENGTH = 64;
    /**
     * Number of iterations of PBKDF2 to run on user PINs to get a crypto key.
     * Higher means it's slower to get a PIN token, but also harder to brute force the device open with physical
     * access to it. Can theoretically be any number but surely there are limits to how long you're willing to
     * wait, and there's no way a smartcard is outcompeting a desktop computer...
     */
    private static final short PIN_KDF_ITERATIONS = 5;
    /**
     * Number of times PIN entry can be attempted before the device will self-lock. FIDO2 standards say eight.
     */
    private static final short MAX_PIN_RETRIES = 8;
    /**
     * How many times a PIN can be incorrectly entered before the authentiator must be rebooted to proceed.
     * FIDO2 standards say three.
     */
    private static final short PIN_TRIES_PER_RESET = 3;

    // Fields for decoding incoming APDUs and encoding outgoing ones
    /**
     * Total byte length of output FIDO2 Credential ID struct.
     * Most authenticators use 64, so you probably want to use 64 as well so creds that come from this authenticator
     * in particular can't be distinguished. The minimum possible value is 32, since credentials need to contain RP
     * ID hashes (which are 32-byte SHA256es). In order to reduce this to 32 you would need to deterministically derive
     * the credential private key from the RP and User IDs instead of storing it inside the credential.
     */
    private static final short CREDENTIAL_ID_LEN = 64;
    /**
     * Length of scratch space used for decrypting credentials - must be long enough to hold a decrypted credential ID
     */
    private static final short PRIVATE_SCRATCH_SIZE = CREDENTIAL_ID_LEN;
    /**
     * Byte length of one EC point
     */
    private static final short KEY_POINT_LENGTH = 32;
    /**
     * Byte length of hashed relying party ID
     */
    private static final short RP_HASH_LEN = 32;
    /**
     * Byte length of hashed client data struct
     */
    private static final short CLIENT_DATA_HASH_LEN = 32;
    /**
     * Required byte length of wrapped incoming PINs. FIDO standards say 64
     */
    private static final short PIN_PAD_LENGTH = 64;
    /**
     * Request/response buffer
     */
    private byte[] bufferMem;
    /**
     * General working memory
     */
    private byte[] scratch;
    /**
     * Decrypted credential ID holder
     */
    private byte[] privateScratch;
    /**
     * True if the device has been locked with a PIN; false in initial boot state before PIN set
     */
    private boolean pinSet;
    /**
     * AESKey.setKey doesn't take a byte array length param, so we use this temp space to store key bytes
     */
    private byte[] privateKeySpace;
    /**
     * Random key for deriving keys for the hmac-secret extension from regular credential private keys
     */
    private final byte[] hmacWrapperBytes;


    // Fields for negotiating auth with the platform
    /**
     * authenticator transient key
     */
    private KeyPair authenticatorKeyAgreementKey;
    /**
     * encrypt/decrypt key to be set based on platform<->authenticator shared secret
     */
    private AESKey sharedSecretAESKey;
    /**
     * verify/hash key to be set based on platform<->authenticator shared secret
     */
    private byte[] sharedSecretVerifyKey;
    /**
     * DH for platform <-> authenticator secure channel
     */
    private final KeyAgreement keyAgreement;
    /**
     * encipher things destined for the platform
     */
    private final Cipher sharedSecretWrapper;
    /**
     * decipher things sent by the platform
     */
    private final Cipher sharedSecretUnwrapper;
    /**
     * must be zeroes. Is used for encryption and decryption of messages between authenticator and platform
     */
    private static final byte[] ZERO_IV = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    /**
     * per-reset random proxy used instead of true user PIN, as proof PIN was obtained
     */
    private byte[] pinToken;
    /**
     * RP ID associated with (restricting) the PIN token, prefixed by a byte which is 0x01 if RP ID set, 0x00 otherwise
     */
    private byte[] permissionsRpId;
    /**
     * Decrypter used to unwrap data by using a key derived from the user's PIN
     */
    private final Cipher pinUnwrapper;
    /**
     * Encrypter used to wrap data by using a key derived from the user's PIN
     */
    private final Cipher pinWrapper;

    /**
     * per-device salt for deriving keys from PINs
     */
    private final byte[] pinKDFSalt;
    /**
     * key to be set from KDF derivation of PIN
     */
    private AESKey pinWrapKey;
    /**
     * used for signing the authData we send to the platform, to prove it came from us
     */
    private final Signature attester;

    // Fields for wrapping and unwrapping platform-held blobs
    /**
     * The most important crypto object in the whole application. Stored in non-volatile memory; resetting this
     * renders all issued credentials unusable. Generated on install, regenerated on CTAP-reset-command.
     *
     * If a PIN is set, these bytes are stored encrypted using a key derived from the PIN.
     */
    private final byte[] wrappingKeySpace;
    /**
     * Authenticator-specific master key, decrypted (if necessary) and loaded into transient storage for use
     */
    private final AESKey wrappingKey;
    /**
     * random IV for authenticator private wrapping
     */
    private static byte[] wrappingIV;
    /**
     * first half random data, second half the HMAC of that using the wrapping key:
     * used for checking if a potential wrapping key is the correct one
     */
    private final byte[] wrappingKeyValidation;
    /**
     * encrypt data using authenticator master key
     */
    private final Cipher symmetricWrapper;
    /**
     * decrypt data using authenticator master key
     */
    private final Cipher symmetricUnwrapper;
    /**
     * Randomness.
     */
    private final RandomData random;

    // Fields for maintaining auth state
    /**
     * Ever-increasing number representing how many sig operations we've made
     */
    private final SigOpCounter counter;
    /**
     * Value that decreases with each failed PIN guess
     */
    private final PinRetryCounter pinRetryCounter;

    // Parameters for the real elliptic-curve keys :-)
    /**
     * The actual per-credential key pair
     */
    private KeyPair ecKeyPair;
    /**
     * A private key for an incoming credential
     */
    private ECPrivateKey ecPrivateKey;
    /**
     * General hashing of stuff
     */
    private final MessageDigest sha256;
    /**
     * Used to make sure the user REALLY wants to reset their authenticator
     */
    private boolean resetRequested;
    /**
     * Everything that needs to be hot in RAM instead of stored to the flash. All goes away on deselect or reset!
     */
    private TransientStorage transientStorage;

    // Data storage for resident keys
    /**
     * Encrypted-as-usual credential ID fields for resident keys, just like we'd receive in incoming blocks
     * from the platform if they were non-resident
     */
    private final byte[] residentKeyData;
    /**
     * A boolean stating whether the resident key is valid (stored, usable) or not
     */
    private final boolean[] residentKeyValidity;
    /**
     * Encrypted (with the device wrapping key) user ID fields for resident keys
     */
    private final byte[] residentKeyUserIds;
    /**
     * Length of the corresponding user IDs
     */
    private final byte[] residentKeyUserIdLengths;
    /**
     * Encrypted (with the device wrapping key) RP ID fields for resident keys
     */
    private final byte[] residentKeyRPIds;
    /**
     * Length of the corresponding RP IDs
     */
    private final byte[] residentKeyRPIdLengths;
    /**
     * Encrypted (with the device wrapping key) public key X+Y point data for resident keys
     */
    private final byte[] residentKeyPublicKeys;
    /**
     * Set to true for each (valid) resident key that has a "unique" RP - used to speed up
     * enumerating RPs while preserving privacy. Note that this doesn't mean it's the only
     * RK with a particular RP; it is just set on exactly ONE RK for each distinct RP
     */
    private final boolean[] residentKeyUniqueRP;
    /**
     * How many resident key slots are filled
     */
    private byte numResidentCredentials;
    /**
     * How many distinct RPs are present across all resident keys
     */
    private byte numResidentRPs;

    private byte[] aaguid = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    /**
     * Deliver a particular byte array to the platform
     *
     * @param apdu Request/response object
     * @param array Bytes to send
     * @param startOffset Read index into given buffer
     * @param len Length to send, starting at byte zero
     */
    private static void sendByteArray(APDU apdu, byte[] array, short startOffset, short len) {
        byte[] buffer = apdu.getBuffer();
        Util.arrayCopyNonAtomic(array, startOffset, buffer, (short) 0, len);
        apdu.setOutgoingAndSend((short) 0, len);
    }

    /**
     * Sends bytes which are already in the APDU buffer
     * @param apdu Request/response object
     * @param startOffset Offset in APDU buffer of first byte to send
     * @param len Length of content to send
     */
    private void sendNoCopy(APDU apdu, short startOffset, short len) {
        scratchRelease();
        apdu.setOutgoingAndSend(startOffset, len);
    }

    /**
     * Convenience method sending a whole byte array to the platform
     *
     * @param apdu Request/response object
     * @param array Byte array to send in its entirety
     */
    private static void sendByteArray(APDU apdu, byte[] array) {
        sendByteArray(apdu, array, (short) 0, (short) array.length);
    }

    /**
     * Sends a CTAP error (not an ISO7816 error!) to the platform.
     * Also resets any APDU chaining state.
     *
     * @param apdu Request/response object
     * @param sendByte Byte representing the CTAP error state
     */
    private void sendErrorByte(APDU apdu, byte sendByte) {
        transientStorage.clearIterationPointers();
        scratchRelease(); // Just in case

        byte[] buffer = apdu.getBuffer();
        buffer[0] = sendByte;
        apdu.setOutgoingAndSend((short) 0, (short) 1);
        throwException(ISO7816.SW_NO_ERROR);
    }

    /**
     * Allocates some in-memory scratch space. Consecutive calls always give contiguous memory.
     * After a successful call, the requested amount of space is reserved in scratch at the returned
     * offset. This memory should be freed with a corresponding call to scratchRelease in reverse order.
     *
     * @param numBytes Amount of scratch space, in bytes, to allocate
     *
     * @return Index of the allocation's start in the scratch buffer
     */
    private short scratchAlloc(short numBytes) {
        short ret = transientStorage.getScratchFillLevel();
        if (ret > (short)(SCRATCH_SIZE - numBytes)) {
            throwException(ISO7816.SW_FILE_FULL);
        }
        transientStorage.increaseScratchFillLevel(numBytes);
        return ret;
    }

    /**
     * Releases ALL scratch space allocated through scratchAlloc
     */
    private void scratchRelease() {
        transientStorage.clearScratchFillLevel();
    }

    /**
     * Releases the most recent allocation(s) of scratch space totaling exactly the given
     * number of bytes. Behavior undefined (silent corruption) if the most recent non-released
     * allocation(s) do NOT exactly total numBytes. There is no way to release earlier allocations
     * except through correctly ordered release calls.
     *
     * @param numBytes The number of bytes to release
     */
    private void scratchRelease(short numBytes) {
        transientStorage.decreaseScratchFillLevel(numBytes);
    }

    /**
     * Fully read an incoming request into either the APDU buffer or bufferMem. In the event the request is larger
     * than the buffer, throws an exception. After call, request chaining variables will
     * be set appropriately if the request is part of a chained sequence.
     *
     * @param apdu Request/response object
     * @param buffer APDU data buffer (not bufferMem)
     * @param lc Length of request received from the platform
     * @param amtRead Amount of the request already read into the APDU buffer
     *
     * @return Buffer which contains the request data (bufferMem or given APDU buffer object)
     */
    private byte[] fullyReadReq(APDU apdu, byte[] buffer, short lc, short amtRead, boolean forceBuffering) {
        transientStorage.clearAssertIterationPointer();
        short chainOff = transientStorage.getChainIncomingReadOffset();

        if (!forceBuffering && chainOff == 0 && lc <= 256) {
            // Single-buffer packed case
            // Shift down so meaningful data start at offset 0
            Util.arrayCopyNonAtomic(buffer, apdu.getOffsetCdata(),
                    buffer, (short) 0, amtRead);
            short read = apdu.receiveBytes(amtRead);
            if (((short)(read + amtRead) != lc)) {
                throwException(ISO7816.SW_WRONG_LENGTH);
            }
            transientStorage.resetChainIncomingReadOffset();
            return buffer;
        }

        // Buffering read case
        Util.arrayCopyNonAtomic(buffer, apdu.getOffsetCdata(),
                bufferMem, chainOff, amtRead);

        short curRead = amtRead;
        while (curRead < lc) {
            short read = apdu.receiveBytes((short) 0);

            if (read == 0) {
                throwException(ISO7816.SW_UNKNOWN);
            }

            if (curRead > (short) (bufferMem.length - read)) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_REQUEST_TOO_LARGE);
            }
            Util.arrayCopyNonAtomic(buffer, (short) 0,
                    bufferMem, (short) (curRead + chainOff), read);
            curRead = (short) (curRead + read);
        }

        if (curRead > lc) {
            transientStorage.resetChainIncomingReadOffset();
            throwException(ISO7816.SW_WRONG_LENGTH);
        }

        if (!apdu.isCommandChainingCLA()) {
            transientStorage.resetChainIncomingReadOffset();
        }

        return bufferMem;
    }

    /**
     * Implements the core FIDO2 CTAP2 makeCredential API
     *
     * @param apdu Request/response object
     * @param buffer Byte buffer containing input request
     * @param lc Length of the request, as sent by the platform
     * @param readIdx Read index into the request buffer
     */
    private void makeCredential(APDU apdu, short lc, byte[] buffer, short readIdx) {
        if (lc == 0) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        if (resetRequested) {
            resetRequested = false;
        }

        if ((buffer[readIdx] & 0xF0) != 0xA0) { // short-entry-count map
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }

        short numParameters = (short) (buffer[readIdx++] & 0x0F);
        if (numParameters < 4) { // There's no valid makeCredential call with fewer than four params
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        if (buffer[readIdx++] != 0x01) { // clientDataHash
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        if (buffer[readIdx] == 0x58) {
            // one-byte length, then bytestr
            if (buffer[++readIdx] != CLIENT_DATA_HASH_LEN) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
        } else {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }

        short clientDataHashIdx = (short) (readIdx + 1);
        readIdx += CLIENT_DATA_HASH_LEN + 1; // we checked above this is indeed the length of the client data hash
        if (readIdx >= lc) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }

        if (buffer[readIdx++] != 0x02) { // rp
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }
        if (readIdx >= lc) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }

        readIdx = consumeMapAndGetID(apdu, buffer, readIdx, lc, false, false);
        short rpIdIdx = transientStorage.getStoredIdx();
        short rpIdLen = transientStorage.getStoredLen();

        if (buffer[readIdx++] != 0x03) { // user
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }
        if (readIdx >= lc) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }

        readIdx = consumeMapAndGetID(apdu, buffer, readIdx, lc, true, false);
        short userIdIdx = transientStorage.getStoredIdx();
        short userIdLen = transientStorage.getStoredLen();
        if (userIdLen > MAX_USER_ID_LENGTH) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_REQUEST_TOO_LARGE);
        }

        if (buffer[readIdx++] != 0x04) { // pubKeyCredParams
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }
        if (readIdx >= lc) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }

        byte pubKeyCredParamsType = buffer[readIdx++];
        if (readIdx >= lc) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }
        if ((pubKeyCredParamsType & 0xF0) != 0x80) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }

        // We only support one algorithm, so let's find that one.
        boolean foundES256 = false;
        short numPubKeys = (short)(pubKeyCredParamsType & 0x0F);
        for (short i = 0; i < numPubKeys; i++) {
            readIdx = checkIfPubKeyBlockSupported(apdu, buffer, readIdx, lc);
            if (transientStorage.hasFoundKeyMatch()) {
                foundES256 = true;
                break;
            }
        }

        if (!foundES256) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_UNSUPPORTED_ALGORITHM);
        }

        boolean hmacSecretEnabled = false;
        byte credProtectLevel = 0;
        boolean pinAuthSuccess = false;

        defaultOptions();

        short excludeListStartIdx = -1;
        short numExcludeListEntries = 0;
        byte pinProtocol = 1;
        short pinAuthIdx = -1;

        // Consume any remaining parameters
        byte lastMapKey = 0x04;
        for (short i = 4; i < numParameters; i++) {
            if (buffer[readIdx] <= lastMapKey) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }
            lastMapKey = buffer[readIdx];

            if (buffer[readIdx] == 0x05) { // excludeList
                short excludeListTypeVal = ub(buffer[(short)(readIdx + 1)]);
                if (excludeListTypeVal < 0x80 || excludeListTypeVal > 0x97) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }

                numExcludeListEntries = (short)(excludeListTypeVal - 0x80);
                excludeListStartIdx = (short)(readIdx + 2);
                readIdx++;
            } else if (buffer[readIdx] == 0x06) { // extensions
                if ((buffer[++readIdx] & 0xA0) != 0xA0) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
                short numExtensions = (short) (buffer[readIdx++] & 0x0F);
                for (short j = 0; j < numExtensions; j++) {
                    if (buffer[readIdx] < 0x61 || buffer[readIdx] > 0x77) {
                        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                    }
                    short sLen = (short) (buffer[readIdx] - 0x60);
                    readIdx++;
                    if (readIdx >= (short)(lc - sLen)) {
                        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                    }
                    if (sLen == CannedCBOR.HMAC_SECRET_EXTENSION_ID.length &&
                            Util.arrayCompare(buffer, readIdx,
                                    CannedCBOR.HMAC_SECRET_EXTENSION_ID, (short) 0, sLen) == 0) {
                        readIdx += sLen;
                        if (buffer[readIdx] != (byte) 0xF5 && buffer[readIdx] != (byte) 0xF4) {
                            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                        }
                        hmacSecretEnabled = buffer[readIdx++] == (byte) 0xF5;
                    } else if (sLen == CannedCBOR.CRED_PROTECT_EXTENSION_ID.length &&
                                Util.arrayCompare(buffer, readIdx,
                                        CannedCBOR.CRED_PROTECT_EXTENSION_ID, (short) 0, sLen) == 0) {
                        readIdx += sLen;
                        credProtectLevel = buffer[readIdx++];
                        if (credProtectLevel != 0x01 && credProtectLevel != 0x02 && credProtectLevel != 0x03) {
                            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_UNSUPPORTED_OPTION);
                        }
                    } else {
                        readIdx += sLen;
                        readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
                        if (readIdx >= lc) {
                            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                        }
                    }
                }
                continue;
            } else if (buffer[readIdx] == 0x07) { // options
                readIdx++;
                readIdx = processOptionsMap(apdu, buffer, readIdx, lc);
                continue;
            } else if (buffer[readIdx] == 0x08) { // pinAuth
                pinAuthIdx = ++readIdx;
                // Read past this, because we need the pinProtocol option first
                readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
                continue;
            } else if (buffer[readIdx] == 0x09) { // pinProtocol
                pinProtocol = buffer[++readIdx];
                checkPinProtocolSupported(apdu, pinProtocol);
                continue;
            } else {
                readIdx++;
            }

            if (readIdx >= lc) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }
            readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
        }

        if (pinAuthIdx != -1) {
            // Come back and verify PIN auth
            verifyPinAuth(apdu, buffer, pinAuthIdx, buffer, clientDataHashIdx, pinProtocol, false);

            if ((transientStorage.getPinPermissions() & FIDOConstants.PERM_MAKE_CREDENTIAL) == 0) {
                // PIN token doesn't have permission for the MC operation
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
            }

            pinAuthSuccess = true;
        }

        if (!pinAuthSuccess) {
            if (pinSet) {
                // PIN is set, but no PIN-auth option was provided
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_REQUIRED);
            }
            if (!ALLOW_RESIDENT_KEY_CREATION_WITHOUT_PIN && transientStorage.hasRKOption()) {
                // Don't allow storing resident keys without a PIN set
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_OPERATION_DENIED);
            }
        }
        loadWrappingKeyIfNoPIN();

        final short scratchRPIDHashOffset = scratchAlloc(RP_HASH_LEN);
        short digested = sha256.doFinal(buffer, rpIdIdx, rpIdLen, scratch, scratchRPIDHashOffset);
        if (digested != RP_HASH_LEN) {
            throwException(ISO7816.SW_UNKNOWN);
        }

        if (pinAuthSuccess) {
            if (permissionsRpId[0] == 0x00) {
                // No permissions RP ID set - we got this PIN token using default permissions.
                // We can proceed here, but we need to bind the current PIN token to this RP ID now.
                permissionsRpId[0] = 0x01;
                Util.arrayCopyNonAtomic(scratch, scratchRPIDHashOffset,
                        permissionsRpId, (short) 1, RP_HASH_LEN);
            } else {
                // Permissions RP ID is set - check it
                if (Util.arrayCompare(scratch, scratchRPIDHashOffset,
                        permissionsRpId, (short) 1, RP_HASH_LEN) != 0) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
                }
            }
        }

        // Check excludeList. This is deferred to here so it's after we check PIN auth...
        if (numExcludeListEntries > 0) {
            short excludeListCredSpace = scratchAlloc(CREDENTIAL_ID_LEN);

            short excludeReadIdx = excludeListStartIdx;
            for (short excludeListIdx = 0; excludeListIdx < numExcludeListEntries; excludeListIdx++) {
                excludeReadIdx = consumeMapAndGetID(apdu, buffer, excludeReadIdx, lc, true, true);
                short credIdIdx = transientStorage.getStoredIdx();
                short credIdLen = transientStorage.getStoredLen();
                if (credIdLen != CREDENTIAL_ID_LEN) {
                    // ruh-roh, the exclude list has bogus stuff in it...
                    // it could be a credential ID from some OTHER authenticator, so ignore it.
                    continue;
                }

                if (checkCredential(buffer, credIdIdx, CREDENTIAL_ID_LEN,
                        scratch, scratchRPIDHashOffset,
                        scratch, excludeListCredSpace)) {
                    // This cred decodes valid and is for the given RPID - fail early.
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CREDENTIAL_EXCLUDED);
                }
            }

            scratchRelease(CREDENTIAL_ID_LEN);
        }

        // Done getting params - make a keypair. You know, what we're supposed to do in this function?
        // Well, we're getting to it, only 150 lines in.
        // We sometimes reset the private key, which clears its curve data, so reset that here
        P256Constants.setCurve((ECPrivateKey) ecKeyPair.getPrivate());
        ecKeyPair.genKeyPair();

        final short scratchPublicKeyOffset = scratchAlloc((short) (KEY_POINT_LENGTH * 2 + 1));
        final short wLen = ((ECPublicKey) ecKeyPair.getPublic()).getW(scratch, scratchPublicKeyOffset);
        if (scratch[scratchPublicKeyOffset] != 0x04) {
            // EC algorithm returned a compressed point... we can't decode that...
            throwException(ISO7816.SW_UNKNOWN);
        }
        if (wLen != 2 * KEY_POINT_LENGTH + 1) {
            throwException(ISO7816.SW_UNKNOWN);
        }

        // From here on down, privateScratch will contain the new credential ID
        encodeCredentialID(apdu, (ECPrivateKey) ecKeyPair.getPrivate(), scratch, scratchRPIDHashOffset);

        // if we're making a resident key, we need to, you know, save that for later
        if (transientStorage.hasRKOption()) {
            short decodedCredOffset = scratchAlloc(CREDENTIAL_ID_LEN);
            short rkSpecificScratchAlloc = CREDENTIAL_ID_LEN;

            final short scratchUserIdOffset = scratchAlloc(MAX_USER_ID_LENGTH);
            rkSpecificScratchAlloc += MAX_USER_ID_LENGTH;

            final short scratchRpIdOffset = scratchAlloc(MAX_RESIDENT_RP_ID_LENGTH);
            rkSpecificScratchAlloc += MAX_RESIDENT_RP_ID_LENGTH;
            rpIdLen = truncateRPId(buffer, rpIdIdx, rpIdLen,
                    scratch, scratchRpIdOffset);

            short targetRKSlot = -1;
            short scannedRKs = 0;
            boolean foundMatchingRK = false;
            boolean foundRPMatchInRKs = false;
            for (short i = 0; i < NUM_RESIDENT_KEY_SLOTS; i++) {
                if (!residentKeyValidity[i]) {
                    if (targetRKSlot == -1) {
                        targetRKSlot = i;
                    }
                    // Don't decode empty/non-valid credentials
                    continue;
                }

                if (checkCredential(
                        residentKeyData, (short) (i * CREDENTIAL_ID_LEN), CREDENTIAL_ID_LEN,
                        scratch, scratchRPIDHashOffset,
                        scratch, decodedCredOffset)) {
                    // This credential matches the RP we're looking at.
                    foundRPMatchInRKs = true;

                    // ... but it might not match the user ID we're requesting...
                    if (userIdLen == residentKeyUserIdLengths[i]) {
                        // DECRYPT the encrypted user ID we stored for this RK, so we can compare
                        symmetricUnwrapper.doFinal(
                                residentKeyUserIds, (short) (i * MAX_USER_ID_LENGTH), MAX_USER_ID_LENGTH,
                                scratch, scratchUserIdOffset
                        );

                        if (Util.arrayCompare(
                                buffer, userIdIdx,
                                scratch, scratchUserIdOffset, userIdLen
                                ) == 0) {
                            // ... this credential is a perfect match - overwrite it
                            foundMatchingRK = true;
                            targetRKSlot = i;
                            break;
                        }
                    }
                }

                if (++scannedRKs == numResidentCredentials && targetRKSlot != -1) {
                    // No more RKs, and we already found a target slot...
                    break;
                }
            }

            if (targetRKSlot == -1) {
                // We're entirely full up...
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_KEY_STORE_FULL);
            }

            // Stow the new credential in the slot we chose earlier
            // we will need the zero-padded user ID
            Util.arrayCopyNonAtomic(buffer, userIdIdx, scratch, scratchUserIdOffset, userIdLen);
            if (userIdLen < MAX_USER_ID_LENGTH) {
                Util.arrayFillNonAtomic(scratch, (short)(scratchUserIdOffset + userIdLen),
                        (short)(MAX_USER_ID_LENGTH - userIdLen), (byte) 0x00);
            }

            JCSystem.beginTransaction();
            boolean ok = false;
            try {
                Util.arrayCopy(privateScratch, (short) 0,
                        residentKeyData, (short) (targetRKSlot * CREDENTIAL_ID_LEN), CREDENTIAL_ID_LEN);
                residentKeyUserIdLengths[targetRKSlot] = (byte) userIdLen;
                symmetricWrapper.doFinal(scratch, scratchUserIdOffset, MAX_USER_ID_LENGTH,
                        residentKeyUserIds, (short) (targetRKSlot * MAX_USER_ID_LENGTH));
                residentKeyRPIdLengths[targetRKSlot] = (byte) rpIdLen;
                symmetricWrapper.doFinal(scratch, scratchRpIdOffset, MAX_RESIDENT_RP_ID_LENGTH,
                        residentKeyRPIds, (short) (targetRKSlot * MAX_RESIDENT_RP_ID_LENGTH));
                symmetricWrapper.doFinal(scratch, (short)(scratchPublicKeyOffset + 1), (short)(KEY_POINT_LENGTH * 2),
                        residentKeyPublicKeys, (short) (targetRKSlot * KEY_POINT_LENGTH * 2));
                residentKeyValidity[targetRKSlot] = true;
                if (!foundMatchingRK) {
                    // We're filling an empty slot
                    numResidentCredentials++;
                }
                residentKeyUniqueRP[targetRKSlot] = !foundRPMatchInRKs;
                if (!foundRPMatchInRKs) {
                    numResidentRPs++;
                }
                ok = true;
            } finally {
                if (ok) {
                    JCSystem.commitTransaction();
                } else {
                    JCSystem.abortTransaction();
                }
            }

            scratchRelease(rkSpecificScratchAlloc);
        }

        // OKAY! time to start actually making the credential and sending a response!
        final short clientDataHashScratchOffset = scratchAlloc(CLIENT_DATA_HASH_LEN);
        Util.arrayCopyNonAtomic(buffer, clientDataHashIdx,
                scratch, clientDataHashScratchOffset, CLIENT_DATA_HASH_LEN);

        // Everything we need is out of the input
        // We're now okay to use the whole bufferMem space to build and send our reply

        // First, preamble
        short outputLen = Util.arrayCopyNonAtomic(CannedCBOR.MAKE_CREDENTIAL_RESPONSE_PREAMBLE, (short) 0,
                bufferMem, (short) 0, (short) CannedCBOR.MAKE_CREDENTIAL_RESPONSE_PREAMBLE.length);

        // CBOR requires us to know how long authData is before we can start writing it out...
        // ... so let's calculate that
        final short adLen = getAuthDataLen(true, hmacSecretEnabled, credProtectLevel > 0);

        // DONE: set bit 0 for user present (currently always)
        // DONE: set bit 6 for attestation included (always, for a makeCredential)
        byte flags = 0x41;
        if (pinAuthSuccess) {
            // DONE: set bit 2 for user verified
            flags = (byte)(flags | 0x04);
        }
        if (hmacSecretEnabled || credProtectLevel > 0) {
            // DONE: set bit 7 for extensions
            flags = (byte)(flags | 0x80);
        }

        short adAddlBytes = writeAD(bufferMem, outputLen, adLen, scratch, scratchRPIDHashOffset,
                scratch, (short)(scratchPublicKeyOffset + 1), flags, hmacSecretEnabled, credProtectLevel);

        short offsetForStartOfAuthData = (short) (outputLen + adAddlBytes);
        outputLen += adLen + adAddlBytes;

        // TEMPORARY copy to build signing buffer
        Util.arrayCopyNonAtomic(scratch, clientDataHashScratchOffset,
                bufferMem, outputLen, CLIENT_DATA_HASH_LEN);

        attester.init(ecKeyPair.getPrivate(), Signature.MODE_SIGN);
        short sigLength = attester.sign(bufferMem, offsetForStartOfAuthData, (short)(adLen + CLIENT_DATA_HASH_LEN),
                bufferMem, (short) (outputLen + 1 + CannedCBOR.ATTESTATION_STATEMENT_PREAMBLE.length));
        if (sigLength > 256 || sigLength < 24) {
            throwException(ISO7816.SW_UNKNOWN);
        }

        // Attestation statement
        outputLen = Util.arrayCopyNonAtomic(CannedCBOR.ATTESTATION_STATEMENT_PREAMBLE, (short) 0,
                bufferMem, outputLen, (short) CannedCBOR.ATTESTATION_STATEMENT_PREAMBLE.length);

        bufferMem[outputLen++] = (byte) sigLength;
        outputLen += sigLength;

        // EC key pair COULD be stored in flash (if device doesn't support transient EC privKeys), so might as
        // well clear it out here since we don't need it any more. We'll get its private key back from the credential
        // ID to use later...
        ecKeyPair.getPrivate().clearKey();

        doSendResponse(apdu, outputLen);
    }

    /**
     * If, and only if, no PIN is set, directly initialize symmetric crypto
     * from our flash-stored wrapping key (which should be unencrypted)
     */
    private void loadWrappingKeyIfNoPIN() {
        if (!pinSet) {
            wrappingKey.setKey(wrappingKeySpace, (short) 0);

            initSymmetricCrypto();
        }
    }

    /**
     * Copies a section of a raw RP ID into a target buffer. If it is longer than the max it will be cut down.
     * If it is shorter, it will be zero-padded.
     *
     * @param rpIdBuf Buffer containing the raw RP ID
     * @param rpIdIdx Index of RP ID in the incoming buffer
     * @param rpIdLen Length of the incoming RP ID
     * @param outputBuff Output buffer into which to write truncated/padded values
     * @param outputOff Write index into output buffer
     *
     * @return Length of RP ID after padding/truncation
     */
    private short truncateRPId(byte[] rpIdBuf, short rpIdIdx, short rpIdLen, byte[] outputBuff, short outputOff) {
        if (rpIdLen <= MAX_RESIDENT_RP_ID_LENGTH) {
            Util.arrayCopyNonAtomic(rpIdBuf, rpIdIdx,
                    outputBuff, outputOff, rpIdLen);
        } else {
            // Truncation necessary...
            short colonPos = -1;
            for (short i = 0; i < rpIdLen; i++) {
                if (rpIdBuf[(short)(rpIdIdx + i)] == (byte) ':') {
                    colonPos = i;
                    break;
                }
            }
            short used = 0;

            if (colonPos != -1) {
                short protocolLen = (short)(colonPos + 1);
                short toCopy = protocolLen <= MAX_RESIDENT_RP_ID_LENGTH ? protocolLen : MAX_RESIDENT_RP_ID_LENGTH;

                Util.arrayCopyNonAtomic(rpIdBuf, rpIdIdx,
                        outputBuff, outputOff, toCopy);

                used += toCopy;
            }

            if ((short)(MAX_RESIDENT_RP_ID_LENGTH - used) < 3) {
                // No room for anything but the protocol bit we already copied
                rpIdLen = used;
            } else {
                // Insert ellipsis
                outputBuff[(short)(outputOff + used++)] = (byte) 0xE2;
                outputBuff[(short)(outputOff + used++)] = (byte) 0x80;
                outputBuff[(short)(outputOff + used++)] = (byte) 0xA6;

                // Copy anything else we have room for after the ellipsis
                short toCopy = (short)(MAX_RESIDENT_RP_ID_LENGTH - used);
                Util.arrayCopyNonAtomic(rpIdBuf, (short)(rpIdIdx + used),
                        outputBuff, (short)(outputOff + used), toCopy);
                rpIdLen = MAX_RESIDENT_RP_ID_LENGTH;
            }
        }

        if (rpIdLen < MAX_RESIDENT_RP_ID_LENGTH) {
            // Zero-fill remainder after RP ID
            Util.arrayFillNonAtomic(outputBuff, (short)(outputOff + rpIdLen),
                    (short)(MAX_RESIDENT_RP_ID_LENGTH - rpIdLen), (byte) 0x00);
        }

        return rpIdLen;
    }

    /**
     * Handspun implementation of HMAC-SHA256, to work around lack of hardware support
     *
     * @param apdu Request/response object
     * @param keyBuff Buffer containing 32-byte-long private key
     * @param keyOff Offset of private key in key buffer
     * @param content Buffer containing arbitrary-length content to be HMACed
     * @param contentOff Offset of content in buffer
     * @param contentLen Length of content
     * @param outputBuff Buffer into which output should be written - must have 32 bytes available
     * @param outputOff Write index into output buffer
     * @param useAPDUBuffer If true, use APDU buffer as scratch
     */
    private void hmacSha256(APDU apdu, byte[] keyBuff, short keyOff,
                             byte[] content, short contentOff, short contentLen,
                             byte[] outputBuff, short outputOff, boolean useAPDUBuffer) {
        if (useAPDUBuffer && contentLen > 190) {
            // Too much to safely fit into APDU buffer
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_REQUEST_TOO_LARGE);
        }

        byte[] workingBuffer = useAPDUBuffer ? apdu.getBuffer(): scratch;

        final short scratchAmt = (short) (contentLen + 64);
        short workingFirst = 0;
        short workingSecond = 32;
        short workingMessage = 64;

        if (!useAPDUBuffer) {
            workingFirst = scratchAlloc((short) 32);
            workingSecond = scratchAlloc((short) 32);
            workingMessage = scratchAlloc(contentLen);
        }

        // first half: put key + 32x 0x36 + content into the buffer
        for (short i = 0; i < 32; i++) {
            workingBuffer[(short) (workingFirst + i)] = (byte) (keyBuff[(short)(i + keyOff)] ^ (0x36)); // ipad
        }
        Util.arrayFillNonAtomic(workingBuffer, workingSecond, (short) 32, (byte) 0x36);

        Util.arrayCopyNonAtomic(content, contentOff,
                workingBuffer, workingMessage, contentLen);

        sha256.doFinal(workingBuffer, workingFirst, (short)(64 + contentLen),
                workingBuffer, workingMessage);

        // second half: put key + 32x 0x5c into buffer, then hash into spot adjacent to previous hash
        for (short i = 0; i < 32; i++) {
            workingBuffer[(short) (workingFirst + i)] = (byte) (keyBuff[(short)(i + keyOff)] ^ (0x5c)); // opad
        }
        Util.arrayFillNonAtomic(workingBuffer, workingSecond, (short) 32, (byte) 0x5c);

        sha256.doFinal(workingBuffer, workingFirst, (short) 96, outputBuff, outputOff);

        if (!useAPDUBuffer) {
            scratchRelease(scratchAmt);
        }
    }

    /**
     * Uses the currently-set pinToken to hash some data and compare against a verification value
     *
     * @param apdu          Request/response object
     * @param content       Buffer containing content to HMAC using the pinToken
     * @param contentIdx    Index of content in given buffer
     * @param contentLen    Length of content
     * @param checkAgainst  Buffer containing "correct" hash we're looking for
     * @param checkIdx      Index of correct hash in corresponding buffer
     * @param pinProtocol   Integer PIN protocol version number
     * @param useAPDUBuffer If true, use APDU buffer as temporary storage
     */
    private void checkPinToken(APDU apdu, byte[] content, short contentIdx, short contentLen,
                               byte[] checkAgainst, short checkIdx, byte pinProtocol, boolean useAPDUBuffer) {
        if (pinProtocol != transientStorage.getPinProtocolInUse()) {
            // Can't use PIN protocol 1 with tokens created with v2 or vice versa
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_TOKEN_EXPIRED);
        }

        short checkLength = 16;
        if (pinProtocol == 2) {
            checkLength = 32;
        }

        byte[] tempBuf;
        short tempOff;
        if (useAPDUBuffer) {
            tempBuf = apdu.getBuffer();
            tempOff = 0;
        } else {
            tempBuf = scratch;
            tempOff = scratchAlloc((short) 32);
        }

        hmacSha256(apdu, pinToken, (short) 0,
                   content, contentIdx, contentLen,
                   tempBuf, tempOff, useAPDUBuffer);

        if (Util.arrayCompare(
                tempBuf, tempOff,
                checkAgainst, checkIdx, checkLength
        ) != 0) {
            // PIN token incorrect...
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
        }

        if (!useAPDUBuffer) {
            scratchRelease((short) 32);
        }
    }

    /**
     * Consumes an incoming pinAuth block and checks it matches our set pinToken.
     *
     * @param apdu                 Request/response object
     * @param buffer               Buffer containing incoming request
     * @param readIdx              Read index into request buffer pointing to a 16-byte array (pinAuth)
     * @param clientDataHashBuffer Buffer containing client data hash
     * @param clientDataHashIdx    Index of the hash of the clientData object, as given by the platform
     * @param pinProtocol          Integer PIN protocol number
     * @param useAPDUBuffer        If true, use APDU buffer as temporary space
     *
     * @return New read index into bufferMem after consuming the pinAuth options block
     */
    private short verifyPinAuth(APDU apdu, byte[] buffer, short readIdx, byte[] clientDataHashBuffer, short clientDataHashIdx,
                                byte pinProtocol, boolean useAPDUBuffer) {
        byte desiredLength = 16;
        if (pinProtocol == 2) {
            desiredLength = 32;
        }

        if (desiredLength < 24) {
            if (buffer[readIdx++] != (byte)(0x40 + desiredLength)) { // byte array with included length
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
        } else {
            if (buffer[readIdx++] != 0x58) { // byte array, one-byte length
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
            if (buffer[readIdx++] != desiredLength) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
        }

        checkPinToken(apdu, clientDataHashBuffer, clientDataHashIdx, CLIENT_DATA_HASH_LEN,
                buffer, readIdx, pinProtocol, useAPDUBuffer);

        readIdx += desiredLength;

        return readIdx;
    }

    /**
     * Consumes a CBOR block of public key data, and checks if it represents a supported algorithm.
     * After call, transientStorage foundKeyMatch will be true if the key is compatible.
     *
     * @param apdu Request/response object
     * @param buffer Buffer containing incoming request
     * @param readIdx Read index into bufferMem
     * @param lc Length of incoming request, as sent by the platform
     *
     * @return New read index into bufferMem after consuming public key block
     */
    private short checkIfPubKeyBlockSupported(APDU apdu, byte[] buffer, short readIdx, short lc) {
        transientStorage.resetFoundKeyMatch();

        byte mapDef = buffer[readIdx++];
        if ((mapDef & 0xF0) != 0xA0) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }
        short numMapEntries = (short)(ub(mapDef) - ub((byte) 0xA0));
        if (numMapEntries < 2) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }
        if (readIdx >= lc) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }
        if (buffer[readIdx++] != 0x63) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }
        if (readIdx >= (short)(lc-4)) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }
        if (buffer[readIdx++] != 'a' || buffer[readIdx++] != 'l' || buffer[readIdx++] != 'g') {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        byte algIntType = buffer[readIdx++];
        if (algIntType == 0x26) { // ES256...
            transientStorage.setFoundKeyMatch();
        } else if (algIntType == 0x38 || algIntType == 0x18) {
            readIdx++;
        } else if (algIntType == 0x39 || algIntType == 0x19) {
            readIdx += 2;
        } else if (!(algIntType >= (byte)0x20 && algIntType <= (byte)0x37)
            && !(ub(algIntType) >= 0x00 && algIntType <= (byte) 0x17)) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }

        // Skip "type" val
        if ((buffer[readIdx] & 0xF0) != 0x60) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }
        short valLen = (short) (buffer[readIdx] & 0x0F);
        readIdx += valLen + 1;
        if (readIdx >= lc) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }

        if ((buffer[readIdx] & 0xF0) != 0x60) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }
        short typeValLen = (short) (buffer[readIdx] & 0x0F);
        if (typeValLen != CannedCBOR.PUBLIC_KEY_TYPE.length) {
            // not the same length as type "public-key": can't be a match
            transientStorage.resetFoundKeyMatch();
        } else if (Util.arrayCompare(buffer, (short)(readIdx + 1),
                CannedCBOR.PUBLIC_KEY_TYPE, (short) 0, typeValLen) != 0) {
            // Not of type "public-key", although same length
            transientStorage.resetFoundKeyMatch();
        }
        readIdx += typeValLen + 1;
        if (readIdx >= lc) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }

        for (short i = 2; i < numMapEntries; i++) {
            readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
        }

        return readIdx;
    }

    /**
     * Write the portions of an authData block that are used for both makeCredential and getAssertion
     *
     * @param outBuf Buffer into which to write
     * @param adLen Length of the overall AD block
     * @param writeIdx Write index into bufferMem
     * @param flags CTAP2 "flags" byte value
     * @param rpIdBuffer Buffer containing a hash of the RP ID
     * @param rpIdOffset Offset of the RP ID hash in the given buffer
     *
     * @return Additional bytes used for AD basic header beyond the minimum
     */
    private short writeADBasic(byte[] outBuf, short adLen, short writeIdx, byte flags, byte[] rpIdBuffer, short rpIdOffset) {
        short ow = writeIdx;
        writeIdx = encodeIntLenTo(outBuf, writeIdx, adLen, true);

        short adAddlBytes = (short)(writeIdx - ow);

        // RPID hash
        writeIdx = Util.arrayCopyNonAtomic(rpIdBuffer, rpIdOffset, outBuf, writeIdx, RP_HASH_LEN);

        outBuf[writeIdx++] = flags; // flags

        // counter
        writeIdx = encodeCounter(outBuf, writeIdx);

        return adAddlBytes;
    }

    /**
     * Encode signature counter into buffer, also incrementing it by one
     *
     * @param buf Buffer into which to write the counter
     * @param off Offset at which to write counter
     *
     * @return New write index into input buffer
     */
    private short encodeCounter(byte[] buf, short off) {
        short ret = counter.pack(buf, off);
        boolean ok = counter.increment();
        if (!ok) {
            throwException(ISO7816.SW_FILE_FULL);
        }
        return ret;
    }

    /**
     * Writes an authenticated data block into the output buffer.
     * Before call, privateScratch must contain the credential ID
     *
     * @param outBuf            Buffer into which to write
     * @param writeIdx          Write index into the given output buffer
     * @param adLen             Total length of the AD block
     * @param rpIdHashBuffer    Buffer containing RP ID hash
     * @param rpIdHashOffset    Offset of the RP ID hash in the buffer
     * @param pubKeyBuffer      Buffer containing the PUBLIC key
     * @param pubKeyOffset      Offset of the public key in the corresponding buffer
     * @param flags             Flags byte to pack into authData object
     * @param hmacSecretEnabled true if the HMAC secret extension is in use
     * @param credProtectLevel  Integer (0-3) for level of credProtect enabled; 0 to disable
     *
     * @return number of bytes beyond the given adLen which were written to complete the AD block
     */
    private short writeAD(byte[] outBuf,
                          short writeIdx, short adLen, byte[] rpIdHashBuffer, short rpIdHashOffset,
                          byte[] pubKeyBuffer, short pubKeyOffset, byte flags,
                          boolean hmacSecretEnabled, byte credProtectLevel) {
        short adAddlBytes = writeADBasic(outBuf, adLen, writeIdx, flags, rpIdHashBuffer, rpIdHashOffset);
        writeIdx += getAuthDataLen(false, hmacSecretEnabled, credProtectLevel > 0) + adAddlBytes;

        // aaguid
        writeIdx = Util.arrayCopyNonAtomic(aaguid, (short) 0, outBuf, writeIdx, (short) aaguid.length);

        // credential ID length
        writeIdx = Util.setShort(outBuf, writeIdx, CREDENTIAL_ID_LEN);

        writeIdx = Util.arrayCopyNonAtomic(privateScratch, (short) 0,
                outBuf, writeIdx, CREDENTIAL_ID_LEN);

        // Public key
        writeIdx = Util.arrayCopyNonAtomic(CannedCBOR.PUBLIC_KEY_ALG_PREAMBLE, (short) 0,
                outBuf, writeIdx, (short) CannedCBOR.PUBLIC_KEY_ALG_PREAMBLE.length);
        writeIdx = writePubKey(outBuf, writeIdx, pubKeyBuffer, pubKeyOffset);

        short numExtensions = 0;
        if (hmacSecretEnabled) {
            numExtensions++;
        }
        if (credProtectLevel > 0) {
            numExtensions++;
        }

        if (numExtensions > 0) {
            outBuf[writeIdx++] = (byte)(0xA0 + numExtensions);
        }

        if (hmacSecretEnabled) {
            outBuf[writeIdx++] = (byte) (96 + CannedCBOR.HMAC_SECRET_EXTENSION_ID.length);
            writeIdx = Util.arrayCopy(CannedCBOR.HMAC_SECRET_EXTENSION_ID, (short) 0,
                    outBuf, writeIdx, (short) CannedCBOR.HMAC_SECRET_EXTENSION_ID.length);
            outBuf[writeIdx++] = (byte) 0xF5; // boolean true
        }

        if (credProtectLevel > 0) {
            outBuf[writeIdx++] = (byte) (96 + CannedCBOR.CRED_PROTECT_EXTENSION_ID.length);
            writeIdx = Util.arrayCopy(CannedCBOR.CRED_PROTECT_EXTENSION_ID, (short) 0,
                    outBuf, writeIdx, (short) CannedCBOR.CRED_PROTECT_EXTENSION_ID.length);
            outBuf[writeIdx++] = credProtectLevel;
        }

        return adAddlBytes;
    }

    /**
     * Builds a credential ID into privateScratch
     *
     * @param apdu Request/response object
     * @param privKey Private key to pack into credentialID
     * @param rpIdHashBuffer Buffer containing hash of RP ID
     * @param rpIdHashOffset Index of RP ID hash in corresponding buffer
     */
    private void encodeCredentialID(APDU apdu, ECPrivateKey privKey,
                                    byte[] rpIdHashBuffer, short rpIdHashOffset) {
        final short scratchOff = scratchAlloc(KEY_POINT_LENGTH);
        final short sLen = privKey.getS(scratch, scratchOff);
        if (sLen != KEY_POINT_LENGTH) {
            throwException(ISO7816.SW_UNKNOWN);
        }

        // credential ID
        // Encrypt: PrivKey || rpIdHash
        // 32 + 32 = 64 bytes
        short encryptedBytes = symmetricWrapper.update(scratch, scratchOff, sLen,
                privateScratch, (short) 0);
        encryptedBytes += symmetricWrapper.doFinal(rpIdHashBuffer, rpIdHashOffset, RP_HASH_LEN,
                privateScratch, encryptedBytes);
        if (encryptedBytes > CREDENTIAL_ID_LEN) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_REQUEST_TOO_LARGE);
        }

        scratchRelease(KEY_POINT_LENGTH);
    }

    /**
     * Pack an EC public key into the given buffer
     *
     * @param outBuf Buffer into which to write
     * @param outputLen The current index in the output buffer (begin writing here)
     * @param pubKeyBuffer A buffer containing the public key to be written in the format X || Y
     * @param pubKeyOffset An index pointing to the X-coordinate of the public key
     *
     * @return New index in the output buffer after writes
     */
    private short writePubKey(byte[] outBuf, short outputLen, byte[] pubKeyBuffer, short pubKeyOffset) {
        outputLen = Util.arrayCopyNonAtomic(pubKeyBuffer, pubKeyOffset,
                outBuf, outputLen, KEY_POINT_LENGTH);
        outBuf[outputLen++] = 0x22; // map key: y-coordinate
        outBuf[outputLen++] = 0x58; // byte string with one-byte length to follow
        outBuf[outputLen++] = (byte) KEY_POINT_LENGTH;
        outputLen = Util.arrayCopyNonAtomic(pubKeyBuffer, (short) (pubKeyOffset + KEY_POINT_LENGTH),
                outBuf, outputLen, KEY_POINT_LENGTH);
        return outputLen;
    }

    /**
     * Calculates the length of an auth data segment
     *
     * @param includeAttestedKey If true, includes attestion data - for makeCredential
     * @param useHmacSecret If true, includes the bytes for the hmac-secret extension
     *
     * @return The number of bytes in the authentication data segment
     */
    private short getAuthDataLen(boolean includeAttestedKey, boolean useHmacSecret, boolean useCredProtect) {
        short basicLen = (short) (RP_HASH_LEN + // RP ID hash
                1 + // flags byte
                4 // counter
        );

        if (!includeAttestedKey) {
            return basicLen;
        }

        return (short) (basicLen +
                (short) aaguid.length + // aaguid
                2 + // credential ID length
                CREDENTIAL_ID_LEN + // credential ID
                CannedCBOR.PUBLIC_KEY_ALG_PREAMBLE.length + // preamble for cred public key
                KEY_POINT_LENGTH + // x-point
                3 + // CBOR bytes to introduce the y-point
                KEY_POINT_LENGTH + // y-point
                (useCredProtect || useHmacSecret ? 1 : 0) + // extension data intro
                (useHmacSecret ? 2 + CannedCBOR.HMAC_SECRET_EXTENSION_ID.length : 0) + // extension data
                (useCredProtect ? 2 + CannedCBOR.CRED_PROTECT_EXTENSION_ID.length : 0) // more extension data
        );
    }

    /**
     * Handles a CTAP2 getAssertion or getNextAssertion API call.
     * Note that this method is called a second time for getNextAssertion, so it needs to preserve (and restore) state
     * in scratch.
     *
     * @param apdu The request/response object
     * @param lc The declared request length
     * @param buffer Buffer containing input request
     * @param readIdx The current read offset into buffer memory
     * @param firstCredIdx The first credential to consider in resident key storage
     */
    private void getAssertion(APDU apdu, short lc, byte[] buffer, short readIdx, short firstCredIdx) {
        short scratchRPIDHashIdx = scratchAlloc(RP_HASH_LEN);
        short clientDataHashIdx = scratchAlloc(CLIENT_DATA_HASH_LEN);
        short pinInfoIdx = scratchAlloc((short) 2); // first byte, PIN protocol. Second byte, 1 if PIN auth success
        short hmacSaltIdx = scratchAlloc((short) 65); // first byte length, remaining bytes HMAC salt
        short hmacSecretBytes = 0;

        byte numMatchesThisRP = 0;
        short rkMatch = -1;
        short allowListLength = 0;
        byte[] matchingPubKeyBuffer = buffer;
        short matchingPubKeyCredDataLen = 0;
        short startOfMatchingPubKeyCredData = (short) -1;

        if (resetRequested) {
            resetRequested = false;
        }

        if (firstCredIdx == 0) { // Start of iteration: actually parse request
            scratch[pinInfoIdx] = 1; // default to PIN protocol one
            scratch[(short)(pinInfoIdx + 1)] = 0;
            scratch[hmacSaltIdx] = 0;

            if (lc == 0) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
            }

            if ((buffer[readIdx] & 0xF0) != 0xA0) { // map with relatively few entries
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }

            short numParams = (short) (buffer[readIdx++] & 0x0F);
            if (numParams < 2) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
            }
            if (readIdx >= lc) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }

            if (buffer[readIdx++] != 0x01) { // rpId
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
            }
            if (readIdx >= lc) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }

            short rpIdLen = 0;
            if (buffer[readIdx] == 0x78) { // one-byte length
                readIdx++;
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
                rpIdLen = buffer[readIdx++];
            } else if (buffer[readIdx] >= 0x61 && buffer[readIdx] < 0x78) { // zero-byte packed length
                rpIdLen = (short) (buffer[readIdx] - 0x60);
                readIdx++;
            } else {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
            short rpIdIdx = readIdx;
            readIdx += rpIdLen;
            if (readIdx >= lc) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }

            if (buffer[readIdx++] != 0x02) { // clientDataHash
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
            }
            short clientDataHashLen = 0;
            if (buffer[readIdx++] == 0x58) {
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
                clientDataHashLen = buffer[readIdx++];
            }
            if (clientDataHashLen != 32) {
                sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_PARAMETER);
            }
            Util.arrayCopyNonAtomic(buffer, readIdx,
                    scratch, clientDataHashIdx, CLIENT_DATA_HASH_LEN);
            readIdx += CLIENT_DATA_HASH_LEN;
            if (readIdx >= lc) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }

            short digested = sha256.doFinal(buffer, rpIdIdx, rpIdLen, scratch, scratchRPIDHashIdx);
            if (digested != RP_HASH_LEN) {
                throwException(ISO7816.SW_UNKNOWN);
            }

            short allowListIdx = -1;

            short paramsRead = 2;
            if (numParams > 2 && buffer[readIdx] == 0x03) { // allowList
                readIdx++;
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }

                // We need to defer this until after we do PIN processing
                allowListIdx = readIdx;
                paramsRead++;
                readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
            }

            defaultOptions();
            short pinAuthIdx = -1;
            short hmacSecretReadIdx = -1;

            // Consume any remaining parameters
            byte lastMapKey = 0x03; // Doesn't matter if it's actually 0x02 or whatever, we just need them in order
            for (short i = paramsRead; i < numParams; i++) {
                byte mapKey = buffer[readIdx++];
                if (mapKey <= lastMapKey) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
                lastMapKey = mapKey;
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }

                if (mapKey == 0x04) { // extensions
                    if ((buffer[readIdx] & 0xA0) != 0xA0) {
                        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                    }

                    short mapEntries = (short) (buffer[readIdx++] & 0x0F);
                    for (short j = 0; j < mapEntries; j++) {
                        if ((buffer[readIdx] & 0x60) != 0x60) {
                            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                        }
                        short keyLength = (short) (buffer[readIdx++] & 0x0F);
                        if (keyLength != CannedCBOR.HMAC_SECRET_EXTENSION_ID.length || Util.arrayCompare(buffer, readIdx,
                                CannedCBOR.HMAC_SECRET_EXTENSION_ID, (short) 0, (short) CannedCBOR.HMAC_SECRET_EXTENSION_ID.length) != 0) {
                            // Extension that is NOT hmac-secret: ignore it
                            readIdx = consumeAnyEntity(apdu, buffer, (short) (readIdx + keyLength), lc);
                            continue;
                        }

                        readIdx += keyLength;

                        // We've got a case of hmac-secret extension params!
                        // store the index and revisit it later, when we've handled the PIN protocol
                        hmacSecretReadIdx = readIdx;
                        readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
                    }

                    continue;
                } else if (mapKey == 0x05) { // options
                    readIdx = processOptionsMap(apdu, buffer, readIdx, lc);
                    continue;
                } else if (mapKey == 0x06) { // pinAuth
                    pinAuthIdx = readIdx;
                    // Read past this and come back later, when pinProtocol is set correctly
                    readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
                    continue;
                } else if (mapKey == 0x07) { // pinProtocol
                    scratch[pinInfoIdx] = buffer[readIdx++];
                    checkPinProtocolSupported(apdu, scratch[pinInfoIdx]);
                    continue;
                }

                readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
            }

            if (pinAuthIdx != -1) {
                verifyPinAuth(apdu, buffer, pinAuthIdx, scratch, clientDataHashIdx, scratch[pinInfoIdx], true);

                if ((transientStorage.getPinPermissions() & FIDOConstants.PERM_GET_ASSERTION) == 0) {
                    // PIN token doesn't have permission for the GA operation
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
                }

                scratch[(short)(pinInfoIdx + 1)] = 1;
            }

            if (pinSet && scratch[(short)(pinInfoIdx + 1)] == 1 && transientStorage.hasUVOption()) {
                // When a PIN is set and provided, the "uv" input option MUST NOT be set.
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_OPTION);
            }

            // Report PIN-validation failures before we report lack of matching creds
            if (pinSet) {
                if (transientStorage.getPinProtocolInUse() == 0 || scratch[(short)(pinInfoIdx + 1)] != 1) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_OPERATION_DENIED);
                }
                if (transientStorage.getPinProtocolInUse() != scratch[pinInfoIdx]) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
                }
            }

            if (scratch[(short)(pinInfoIdx + 1)] == 1) {
                if (permissionsRpId[0] == 0x00) {
                    // getting an assertion with a PIN token created with default perms binds it to this RP ID
                    permissionsRpId[0] = 0x01;
                    Util.arrayCopyNonAtomic(scratch, scratchRPIDHashIdx,
                            permissionsRpId, (short) 1, RP_HASH_LEN);
                } else {
                    // Permissions RP ID is set - check it
                    if (Util.arrayCompare(scratch, scratchRPIDHashIdx,
                            permissionsRpId, (short) 1, RP_HASH_LEN) != 0) {
                        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
                    }
                }
            }

            loadWrappingKeyIfNoPIN();

            if (allowListIdx != -1) {
                short blockReadIdx = allowListIdx;
                if (((byte) (buffer[blockReadIdx] & 0xF0)) != (byte) 0x80) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
                allowListLength = (short) (buffer[blockReadIdx++] & 0x0F);
                if (blockReadIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }

                for (short i = 0; i < allowListLength; i++) {
                    short beforeReadIdx = blockReadIdx;
                    blockReadIdx = consumeMapAndGetID(apdu, buffer, blockReadIdx, lc, true, true);
                    short pubKeyIdx = transientStorage.getStoredIdx();
                    short pubKeyLen = transientStorage.getStoredLen();
                    if (pubKeyLen == -1) {
                        // Invalid allow list entry - ignore
                        continue;
                    }

                    if (i < firstCredIdx) {
                        // Don't check credentials that are before our iteration point, just skip them
                        continue;
                    }

                    if (startOfMatchingPubKeyCredData != -1) {
                        // In CTAP2.1, we are done, as we're supposed to treat the match we already made as the only match
                        // we're just continuing iteration to throw exceptions when we encounter invalid allowList entries
                        // AFTER the valid one
                        continue;
                    }

                    // We need to check all the creds in the list when given an allowList
                    boolean matches = checkCredential(buffer, pubKeyIdx, pubKeyLen, scratch, scratchRPIDHashIdx,
                            privateScratch, (short) 0);
                    if (matches) {
                        numMatchesThisRP++;
                        startOfMatchingPubKeyCredData = beforeReadIdx;
                        matchingPubKeyCredDataLen = (short) (blockReadIdx - startOfMatchingPubKeyCredData);
                        loadPrivateScratchIntoAttester();
                    }
                }
            }

            if (hmacSecretReadIdx != -1) {
                // Come back and load HMAC salts into scratch space
                byte saltLen = extractHMACSalt(apdu, buffer, hmacSecretReadIdx, lc,
                        scratch, (short)(hmacSaltIdx + 1), scratch[pinInfoIdx]);
                scratch[hmacSaltIdx] = saltLen;
            }
        }

        short potentialAssertionIterationPointer = 0;

        if (allowListLength == 0) {
            // Scan resident keys for match
            short scannedRKs = 0;
            for (short i = firstCredIdx; i < NUM_RESIDENT_KEY_SLOTS; i++) {
                if (!residentKeyValidity[i]) {
                    continue;
                }

                if (checkCredential(residentKeyData, (short) (i * CREDENTIAL_ID_LEN), CREDENTIAL_ID_LEN,
                        scratch, scratchRPIDHashIdx,
                        privateScratch, (short) 0)) {
                    // Got a resident key hit!
                    numMatchesThisRP++;
                    if (rkMatch == -1) {
                        potentialAssertionIterationPointer = (short)(i + 1);
                        matchingPubKeyBuffer = residentKeyData;
                        startOfMatchingPubKeyCredData = (short)(i * CREDENTIAL_ID_LEN);
                        matchingPubKeyCredDataLen = CREDENTIAL_ID_LEN;
                        rkMatch = i;
                        loadPrivateScratchIntoAttester();
                    }
                    if (firstCredIdx > 0) {
                        // If we're on a getNextAssertion, we don't need to count the number of matches, so break early
                        break;
                    }
                }

                if (++scannedRKs == numResidentCredentials) {
                    // No more RKs...
                    break;
                }
            }
        }

        if (startOfMatchingPubKeyCredData == (short) -1) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_NO_CREDENTIALS);
        }

        // HMAC will clobber privateScratch, so it needs to be below the private key setup above
        if (scratch[hmacSaltIdx] != 0) {
            hmacSecretBytes = computeHMACSecret(apdu, ecPrivateKey,
                    scratch, (short)(hmacSaltIdx + 1), scratch[hmacSaltIdx],
                    scratch[pinInfoIdx]
            );
        }

        // RESPONSE BELOW HERE
        byte[] outBuf = bufferMem;
        short outputIdx = (short) 0;

        outBuf[outputIdx++] = FIDOConstants.CTAP2_OK;

        byte numMapEntries = 3;
        if (rkMatch > -1) {
            numMapEntries++; // user block
        }
        if (firstCredIdx == 0 && numMatchesThisRP > 1) {
            numMapEntries++; // numberOfCredentials
        }

        outBuf[outputIdx++] = (byte) (0xA0 + numMapEntries); // map with some entry count
        outBuf[outputIdx++] = 0x01; // map key: credential

        // credential
        if (rkMatch > -1) {
            // Resident keys need CBOR wrapping...
            outputIdx = packCredentialId(matchingPubKeyBuffer, startOfMatchingPubKeyCredData,
                    outBuf, outputIdx);
        } else {
            // Copy straight from input to output
            outputIdx = Util.arrayCopyNonAtomic(matchingPubKeyBuffer, startOfMatchingPubKeyCredData,
                    outBuf, outputIdx, matchingPubKeyCredDataLen);
        }

        outBuf[outputIdx++] = 0x02; // map key: authData

        byte flags = transientStorage.hasUPOption() ? (byte) 0x01 : 0x00;
        if (scratch[(short)(pinInfoIdx + 1)] == 1) {
            flags = (byte)(flags | 0x04);
        }

        short adLen = getAuthDataLen(false, false, false);
        short extensionDataLen = 0;
        if (hmacSecretBytes > 0) {
            extensionDataLen = (short) (
                    hmacSecretBytes +
                    4 + // CBOR overhead bytes
                    CannedCBOR.HMAC_SECRET_EXTENSION_ID.length
            );
            flags = (byte)(flags | 0x80);
        }

        // authData (no attestation...)
        short adAddlBytes = writeADBasic(outBuf, (short) (adLen + extensionDataLen), outputIdx, flags,
                scratch, scratchRPIDHashIdx);

        short startOfAD = (short) (outputIdx + adAddlBytes);

        outputIdx = (short) (startOfAD + adLen);
        short beforeExtensionOutputLen = outputIdx;
        if (hmacSecretBytes > 0) {
            outBuf[outputIdx++] = (byte) 0xA1; // map with one item
            outBuf[outputIdx++] = 0x6B; // string: eleven bytes long
            outputIdx = Util.arrayCopyNonAtomic(CannedCBOR.HMAC_SECRET_EXTENSION_ID, (short) 0,
                    outBuf, outputIdx, (short) CannedCBOR.HMAC_SECRET_EXTENSION_ID.length);
            outBuf[outputIdx++] = 0x58; // byte string: one-byte length
            outBuf[outputIdx++] = (byte) hmacSecretBytes;
            // HMAC output was stored into the APDU buffer earlier, and never clobbered
            outputIdx = Util.arrayCopyNonAtomic(apdu.getBuffer(), (short) 0,
                    outBuf, outputIdx, hmacSecretBytes);
        }
        if ((short)(outputIdx - beforeExtensionOutputLen) != extensionDataLen) {
            throwException(ISO7816.SW_UNKNOWN);
        }

        // TEMPORARILY copy the clientDataHash into the output buffer so we have a contiguous signing block
        // We'll overwrite it again in a moment, so don't advance the output write index
        Util.arrayCopyNonAtomic(scratch, clientDataHashIdx,
                outBuf, outputIdx, CLIENT_DATA_HASH_LEN);
        short sigLength = attester.sign(outBuf, startOfAD, (short)(adLen + extensionDataLen + CLIENT_DATA_HASH_LEN),
                outBuf, (short)(outputIdx + 3)); // 3 byte space: map key, byte array type, byte array length
        // After the signature, we are done with the credential private key we loaded.
        // It might be stored in flash, so let's clear that out.
        ecPrivateKey.clearKey();
        if (sigLength > 255 || sigLength < 24) { // would not require exactly one byte to encode the length...
            throwException(ISO7816.SW_UNKNOWN);
        }

        // advance past the signature we just wrote, which overwrote the clientDataHash in the buffer
        outBuf[outputIdx++] = 0x03; // map key: signature
        outBuf[outputIdx++] = 0x58; // one-byte length
        outBuf[outputIdx++] = (byte) sigLength;
        outputIdx += sigLength;

        if (rkMatch > -1) {
            short uidLen = ub(residentKeyUserIdLengths[rkMatch]);

            outBuf[outputIdx++] = 0x04; // map key: user
            outputIdx = Util.arrayCopyNonAtomic(CannedCBOR.SINGLE_ID_MAP_PREAMBLE, (short) 0,
                    outBuf, outputIdx, (short) CannedCBOR.SINGLE_ID_MAP_PREAMBLE.length);
            outputIdx = encodeIntLenTo(outBuf, outputIdx, uidLen, true);
            // Pack the user ID from the resident key into the buffer, after decrypting it
            symmetricUnwrapper.doFinal(residentKeyUserIds, (short) (rkMatch * MAX_USER_ID_LENGTH), MAX_USER_ID_LENGTH,
                    outBuf, outputIdx);
            outputIdx += uidLen; // only advance by the ACTUAL length of the UID, not the padded size
        }

        if (firstCredIdx == 0 && numMatchesThisRP > 1) {
            outBuf[outputIdx++] = 0x05; // map key: numberOfCredentials
            outputIdx = encodeIntTo(outBuf, outputIdx, numMatchesThisRP);
        }

        transientStorage.setAssertIterationPointer(potentialAssertionIterationPointer);
        doSendResponse(apdu, outputIdx);
    }

    /**
     * Sends an error to the client if the given PIN protocol version is not supported
     *
     * @param apdu Request/response object
     * @param pinProtocol Integer PIN protocol version number
     */
    private void checkPinProtocolSupported(APDU apdu, byte pinProtocol) {
        if (pinProtocol != 1 && pinProtocol != 2) {
            sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_PARAMETER);
        }
    }

    /**
     * Initializes the attester using the contents of privateScratch for keying. After call, attestations may be made.
     */
    private void loadPrivateScratchIntoAttester() {
        P256Constants.setCurve(ecPrivateKey);
        ecPrivateKey.setS(privateScratch, (short) 0, (short) 32);
        attester.init(ecPrivateKey, Signature.MODE_SIGN);
    }

    /**
     * Pack the length of a string or byte array as CBOR into a given buffer
     *
     * @param outBuf Buffer into which to write
     * @param writeIdx write index into given buffer
     * @param v Length value to be packed
     * @param byteString If true, value is the length of a byte string; if false, character string
     *
     * @return new write index after packing
     */
    private short encodeIntLenTo(byte[] outBuf, short writeIdx, short v, boolean byteString) {
        if (v < 24) {
            outBuf[writeIdx++] = (byte)((byteString ? 0x40 : 0x60) + v); // string with inline length
        } else if (v < 256) {
            outBuf[writeIdx++] = (byte)(byteString ? 0x58 : 0x78); // string: one byte length
            outBuf[writeIdx++] = (byte) v;
        } else {
            outBuf[writeIdx++] = (byte)(byteString ? 0x59 : 0x79); // string: two-byte length
            writeIdx = Util.setShort(outBuf, writeIdx, v);
        }
        return writeIdx;
    }

    /**
     * Sets in-memory variables capturing possible incoming CTAP options to their default values
     */
    private void defaultOptions() {
        transientStorage.defaultOptions();
    }

    /**
     * Reads a CBOR "options" map from bufferMem. After call, in-memory booleans corresponding to
     * the passed options are set as dictated by the input
     *
     * @param apdu Request/response object
     * @param buffer Buffer containing incoming request
     * @param readIdx Read index into request buffer
     * @param lc Length of incoming request, as sent by the platform
     *
     * @return New read index after consuming the options map object
     */
    private short processOptionsMap(APDU apdu, byte[] buffer, short readIdx, short lc) {
        if ((buffer[readIdx] & 0xF0) != 0xA0) { // map
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }
        short numOptions = (short)(buffer[readIdx++] & 0x0F);
        if (readIdx > (short)(lc - 3)) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }

        for (short j = 0; j < numOptions; j++) {
            if ((buffer[readIdx] & 0xF0) != 0x60) { // string
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
            short optionStrLen = (short)(buffer[readIdx++] & 0x0F);
            if (optionStrLen != 2 || (buffer[readIdx] != 'u' && buffer[readIdx] != 'r')) {
                // unknown option; ignore it and its value
                readIdx += optionStrLen;
                readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
                continue;
            }

            if (buffer[readIdx] == 'r' && buffer[(short)(readIdx+1)] == 'k') {
                // rk option
                readIdx += 2;
                if (buffer[readIdx] == (byte) 0xF5) { // true
                    transientStorage.setRKOption(true);
                } else if (buffer[readIdx] == (byte) 0xF4) { // false
                    transientStorage.setRKOption(false);
                } else {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
            } else {
                short pOrVPos = ++readIdx;

                if (buffer[pOrVPos] != 'p' && buffer[pOrVPos] != 'v') {
                    // unknown two-character option starting with 'u'...
                    readIdx++;
                    readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
                    continue;
                }

                byte val = buffer[++readIdx];
                if (val == (byte) 0xF5) { // true
                    if (buffer[pOrVPos] == 'p') {
                        transientStorage.setUPOption(true);
                    } else {
                        transientStorage.setUVOption(true);
                    }
                } else if (val == (byte) 0xF4) { // false
                    if (buffer[pOrVPos] == 'p') {
                        transientStorage.setUPOption(false);
                    } else {
                        transientStorage.setUVOption(false);
                    }
                } else {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
            }
            readIdx++;
        }

        return readIdx;
    }

    /**
     * After call, DH platform<->authenticator shared secret is available:
     * sharedSecretWrapper and sharedSecretUnwrapper may be used (for PIN protocol one - two requires an IV),
     * and sharedSecretVerifyKey contains the verify HMAC key (for either PIN protocol)
     *
     * @param apdu              Request/response object
     * @param buf               Buffer containing platform public key
     * @param xOff              Index of the platform key's X coordinate in the given buffer
     * @param yOff              Index of the platform key's Y coordinate in the given buffer
     * @param pinProtocol       Integer PIN protocol version in use
     * @param clobberBuffers    If true, uses both APDU buffer (96 bytes) and privateScratch (32 bytes) as temp space
     */
    private void prepareSharedSecret(APDU apdu, byte[] buf, short xOff, short yOff, byte pinProtocol,
                                     boolean clobberBuffers) {
        final short fullKeyLength = KEY_POINT_LENGTH * 2 + 1;

        // Pack the public key into a nice compact representation (mangling the buffer)
        short kpStart = (short)(yOff - KEY_POINT_LENGTH - 1);
        Util.arrayCopyNonAtomic(buf, xOff,
                buf, (short)(kpStart + 1), KEY_POINT_LENGTH); // place X just before Y and just after header
        buf[kpStart] = 0x04; // "Uncompressed" EC point format - this idx is no longer in the x-point after we moved it

        // DH-generate the shared secret... (overwriting the public key we just put in the buffer)
        short rawSecretLength = keyAgreement.generateSecret(
                buf, kpStart, fullKeyLength,
                buf, kpStart
        );

        if (pinProtocol == 1) {
            // This was a "plain" DH so we need to sha256 the result to get the real secret
            short sharedSecretLength = sha256.doFinal(buf, kpStart, rawSecretLength,
                    privateKeySpace, (short) 0);
            if (sharedSecretLength != privateKeySpace.length) {
                throwException(ISO7816.SW_UNKNOWN);
            }

            // Now, finally, the shared secret is ready!
            sharedSecretAESKey.setKey(privateKeySpace, (short) 0);
            Util.arrayCopyNonAtomic(privateKeySpace, (short) 0,
                    sharedSecretVerifyKey, (short) 0, sharedSecretLength);
            sharedSecretWrapper.init(sharedSecretAESKey, Cipher.MODE_ENCRYPT, ZERO_IV, (short) 0, (short) ZERO_IV.length);
            sharedSecretUnwrapper.init(sharedSecretAESKey, Cipher.MODE_DECRYPT, ZERO_IV, (short) 0, (short) ZERO_IV.length);
        } else if (pinProtocol == 2) {
            // HKDF step one: get PRK by using zeros as a key to HMAC-SHA256 the IKM
            // if we're okay to clobber buffers, we need to not use buf as a target because it might be the APDU buffer
            byte[] fBuffer = clobberBuffers ? privateScratch : buf;
            short fOff = clobberBuffers ? 0 : kpStart;

            if (clobberBuffers) {
                Util.arrayCopyNonAtomic(buf, kpStart,
                        fBuffer, fOff, rawSecretLength);
            }

            hmacSha256(apdu, FIDOConstants.ZERO_SALT, (short) 0,
                    fBuffer, fOff, rawSecretLength,
                    fBuffer, fOff, clobberBuffers
            );

            // HKDF step two (for AES key): use PRK as key to HMAC-SHA256 a magic info string (plus a 0x01 byte)
            hmacSha256(apdu, fBuffer, fOff,
                    FIDOConstants.CTAP2_AES_KEY_INFO, (short) 0, (short) FIDOConstants.CTAP2_AES_KEY_INFO.length,
                    privateKeySpace, (short) 0, clobberBuffers
            );
            sharedSecretAESKey.setKey(privateKeySpace, (short) 0);

            // HKDF step two (for HMAC key): use PRK as key to HMAC-SHA256 a different magic info string (plus a 0x01 byte)
            hmacSha256(apdu, fBuffer, fOff,
                    FIDOConstants.CTAP2_HMAC_KEY_INFO, (short) 0, (short) FIDOConstants.CTAP2_HMAC_KEY_INFO.length,
                    sharedSecretVerifyKey, (short) 0, clobberBuffers
            );
        } else {
            sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_PARAMETER);
        }
    }

    /**
     * Pulls decrypted HMAC secret salts out of input CBOR message and places them in an output buffer
     *
     * @param apdu Request/response object
     * @param buffer Buffer containing incoming request
     * @param readIdx Read index into request buffer
     * @param lc Length of incoming request, as sent by the platform
     * @param outBuffer Output buffer into which to pack salt - needs at least 64 bytes of space
     * @param outOffset Offset into output buffer for writing salt
     * @param pinProtocol Integer PIN protocol in use
     *
     * @return Length of decrypted salt written to output buffer (32 or 64 bytes)
     */
    private byte extractHMACSalt(APDU apdu, byte[] buffer, short readIdx, short lc,
                                 byte[] outBuffer, short outOffset, byte pinProtocol) {
        short mapType = buffer[readIdx++];
        if (mapType != (byte) 0xA3 && mapType != (byte) 0xA4) { // map, three or four entries
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }
        if (readIdx >= (short)(lc - (CannedCBOR.PUBLIC_KEY_DH_ALG_PREAMBLE.length + KEY_POINT_LENGTH * 2 + 7))) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }

        if (buffer[readIdx++] != 0x01) { // map key: keyAgreement
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        readIdx = consumeKeyAgreement(apdu, buffer, readIdx, pinProtocol, false);

        if (buffer[readIdx++] != 0x02) { // map key: saltEnc
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }
        if (buffer[readIdx++] != 0x58) { // byte string: one byte length
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }

        byte saltLenWithIV = buffer[readIdx++];
        byte saltLen = saltLenWithIV;
        short expectedBaseLen = 32;
        if (pinProtocol == 2) {
            expectedBaseLen += 16; // 16-byte IV for decryption
        }
        if (saltLenWithIV != expectedBaseLen && saltLenWithIV != (short)(expectedBaseLen + 32)) { // Standard says one or two 32-byte salts
            sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_LENGTH);
        }
        short saltIdx = readIdx;

        if (readIdx >= (short)(lc - saltLenWithIV - 2 - 16)) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }
        if (pinProtocol == 2) {
            saltLen -= 16; // "real" salt length shouldn't include the IV
        }

        final short scratchAmt = 32;
        short scratchOff = scratchAlloc(scratchAmt);
        readIdx = sharedSecretDecrypt(apdu, buffer, readIdx, lc, saltLen,
                outBuffer, outOffset, pinProtocol, false);

        if (buffer[readIdx++] != 0x03) { // map key: saltAuth
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        byte expectedSaltAuthLen = 16;
        if (pinProtocol == 2) {
            expectedSaltAuthLen = 32;
        }
        if (expectedSaltAuthLen < 24) {
            if (buffer[readIdx++] != (byte)(0x40 + expectedSaltAuthLen)) { // byte string with included length
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
        } else {
            if (buffer[readIdx++] != 0x58) { // byte string, one-byte length
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
            if (buffer[readIdx++] != expectedSaltAuthLen) { // fixed length
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
        }

        hmacSha256(apdu, sharedSecretVerifyKey, (short) 0,
                buffer, saltIdx, saltLenWithIV,
                scratch, scratchOff, true
        );

        if (Util.arrayCompare(scratch, scratchOff,
                buffer, readIdx, expectedSaltAuthLen
        ) != 0) {
            // We must have gotten the crypto wrong somehow... (or the platform sent incorrect vals)
            // our computed HMAC didn't match the input
            sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_PARAMETER);
        }

        scratchRelease(scratchAmt);

        readIdx += expectedSaltAuthLen;

        return saltLen;
    }

    /**
     * Does the math for the FIDO2 hmac-secret extension. After call, the APDU
     * buffer (starting at byte 0) contains a blob appropriate to send back to the platform as the value
     * produced by the hmac-secret extension.
     *
     * Note that this function overwrites privateScratch, and will mangle the APDU buffer!
     *
     * @param apdu Request/response object
     * @param privateKey Private key from which to get HMAC secret material
     * @param saltBuf Buffer containing HMAC secret salt(s)
     * @param saltOff Offset in salt buffer for start of salt(s)
     * @param saltLen Length of salt(s)
     * @param pinProtocol Integer PIN protocol version in use
     *
     * @return Length of HMAC secret data in output buffer
     */
    private short computeHMACSecret(APDU apdu, ECPrivateKey privateKey, byte[] saltBuf, short saltOff, short saltLen,
                                    byte pinProtocol) {
        // hmacSha256 in this function will be give 32-byte content lengths, so it will use APDU buffer bytes 0-96
        // that leaves us room in APDU bytes 128-192...
        final byte[] tempBuf = apdu.getBuffer();
        final short tempOff = (short) 128;

        // We will derive the HMAC secret key from the credential private key and an on-device key
        // ... by doing an HMAC-SHA256 of the credential private key using the HMAC-specific on-device key
        privateKey.getS(privateScratch, (short) 0);

        // Derive the HMAC secret from the private key by taking the credential private key,
        // doing an HMAC-SHA256 on it...
        hmacSha256(apdu, hmacWrapperBytes, (short) 0,
                privateScratch, (short) 0, (short) 32,
                privateScratch, (short) 0, true);
        // ... and then use that as our HMAC secret! It's in privateScratch bytes 0-31.

        // HMAC the first salt
        hmacSha256(apdu, privateScratch, (short) 0,
                saltBuf, saltOff, (short) 32,
                tempBuf, tempOff, true);
        if (saltLen == 64) {
            // if there's a second salt, HMAC that too
            hmacSha256(apdu, privateScratch, (short) 0,
                    saltBuf, (short)(saltOff + 32), (short) 32,
                    tempBuf, (short)(tempOff + 32), true);
        }

        // encrypt the salted hashes using the shared secret, and that's our result
        return sharedSecretEncrypt(tempBuf, tempOff, saltLen,
                apdu.getBuffer(), (short) 0, pinProtocol, false);
    }

    /**
     * Deciphers a credential ID block and compares its RP hash ID with a given value.
     * Before call, the symmetric secret must be initialized.
     * After call, the output buffer will contain: credPrivateKey || RPIDHash
     *
     * @param credentialBuffer Buffer containing the credential ID
     * @param credentialIndex Index of the credential ID block in the incoming buffer
     * @param credentialLen Length of the credential ID, as sent by the platform
     * @param rpIdBuf Buffer containing the RP ID hash
     * @param rpIdHashIdx Index of the RP ID hash within the given buffer
     * @param outputBuffer Buffer into which to store the decoded credential ID
     * @param outputOffset Offset into the output buffer for write
     *
     * @return true if the credential decrypts to match the given RP ID hash, false otherwise
     */
    private boolean checkCredential(byte[] credentialBuffer, short credentialIndex, short credentialLen,
                                    byte[] rpIdBuf, short rpIdHashIdx,
                                    byte[] outputBuffer, short outputOffset) {
        if (credentialLen != CREDENTIAL_ID_LEN) {
            // Someone's playing silly games...
            return false;
        }

        short numBytesUnwrapped = symmetricUnwrapper.doFinal(credentialBuffer, credentialIndex,
                credentialLen, outputBuffer, outputOffset);
        if (numBytesUnwrapped != CREDENTIAL_ID_LEN) {
            return false;
        }

        if (Util.arrayCompare(outputBuffer, (short) (outputOffset + 32), rpIdBuf, rpIdHashIdx, RP_HASH_LEN) == 0) {
            return true;
        }

        return false;
    }

    /**
     * Sends a particular given buffer to the platform as a response
     *
     * @param apdu Request/response object
     * @param buffer Buffer to send
     * @param startOffset Read index into given buffer
     * @param outputLen Length of output in bytes
     */
    private void doSendResponseFrom(APDU apdu, byte[] buffer, short startOffset, short outputLen) {
        scratchRelease();

        final short bufferChunkSize = 256;

        if (outputLen < bufferChunkSize || apdu.getOffsetCdata() == ISO7816.OFFSET_EXT_CDATA) {
            // If we're under one chunk or okay to use extended length APDUs, send in one go
            sendByteArray(apdu, buffer, startOffset, outputLen);
        } else {
            // else, send one chunk and set state to continue response delivery later
            apdu.setOutgoing();
            apdu.setOutgoingLength(bufferChunkSize);
            Util.arrayCopyNonAtomic(buffer, startOffset,
                    apdu.getBuffer(), (short) 0, bufferChunkSize);
            apdu.sendBytes((short) 0, bufferChunkSize);
            setupChainedResponse((short)(startOffset + bufferChunkSize), (short)(outputLen - bufferChunkSize));
        }
    }

    /**
     * Sets up state tracking for a chained (long) response to the platform, and sends the appropriate status code.
     * Should only be called after the first packet in the chain is sent.
     *
     * @param offset The offset into the response buffer from which to begin the next packet
     * @param remaining The total number of bytes remaining after the already-sent packet
     */
    private void setupChainedResponse(short offset, short remaining) {
        transientStorage.setOutgoingContinuation(offset, remaining);
        if (remaining >= 256) {
            // at least ANOTHER full chunk remains
            throwException(ISO7816.SW_BYTES_REMAINING_00, false);
        } else {
            // exactly one more chunk remains
            throwException((short) (ISO7816.SW_BYTES_REMAINING_00 + remaining), false);
        }
    }

    /**
     * Sends the request/response buffer to the platform as a response. If the response is too long
     * for the platform - based on whether the incoming request used extended-length APDUs - will
     * send the first chunk and set internal state to continue the response later.
     *
     * @param apdu Request/response object
     * @param outputLen Length of the output buffer, in bytes. Output always starts at index zero
     */
    private void doSendResponse(APDU apdu, short outputLen) {
        doSendResponseFrom(apdu, bufferMem, (short) 0, outputLen);
    }

    /**
     * Gets a byte as a short representing its UNsigned value
     *
     * @param b Byte to convert
     *
     * @return Short integer, always positive, representing the byte as unsigned
     */
    private short ub(byte b) {
        return (short)(0xFF & b);
    }

    /**
     * Consumes and discards an arbitrary CBOR object. Is recursive, so relies on FIDO2 nesting depth standard to
     * avoid stack overflow in valid cases.
     *
     * @param apdu Request/response object
     * @param buffer Buffer containing incoming request
     * @param readIdx Current index into incoming request buffer
     * @param lc Length of request, as sent by the platform
     *
     * @return New index into incoming request buffer after consuming one CBOR object of any type
     */
    private short consumeAnyEntity(APDU apdu, byte[] buffer, short readIdx, short lc) {
        byte b = buffer[readIdx];
        short s = ub(b);

        if ((b >= 0 && b <= 0x17) || (b >= 0x20 && b <= 0x37) || b == (byte)0xF4 || b == (byte)0xF5) {
            return (short)(readIdx + 1);
        }
        if (b == 0x18 || b == 0x38) {
            return (short) (readIdx + 2);
        }
        if (b == 0x19 || b == 0x39) {
            return (short) (readIdx + 3);
        }
        if (b == 0x58 || b == 0x78) {
            return (short) (readIdx + 2 + buffer[(short)(readIdx+1)]);
        }
        if (b == 0x59 || b == 0x79) {
            short len = (short) (buffer[(short)(readIdx + 1)] << 8 + buffer[(short)(readIdx + 2)]);
            return (short) (readIdx + 2 + len);
        }
        if (b >= 0x40 && b <= 0x57) {
            return (short)(readIdx + 1 + b - 0x40);
        }
        if (b >= 0x60 && b <= 0x77) {
            return (short)(readIdx + 1 + b - 0x60);
        }
        if (b == (byte)0x98) {
            short l = ub(buffer[++readIdx]);
            readIdx++;
            for (short i = 0; i < l; i++) {
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
                readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
            }
            return readIdx;
        }
        if (b == (byte)0x99) {
            short l = (short)(buffer[(short)(readIdx + 1)] << 8 + buffer[(short)(readIdx + 2)]);
            if (l == Short.MAX_VALUE) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
            readIdx += 2;
            for (short i = 0; i < l; i++) {
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
                readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
            }
            return readIdx;
        }
        if (s >= 0x80 && s <= 0x97) {
            readIdx++;
            for (short i = 0; i < (short)(s - 0x80); i++) {
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
                readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
            }
            return readIdx;
        }
        if (s >= 0xA0 && s <= 0xB7) {
            readIdx++;
            for (short i = 0; i < (short)(s - 0xA0); i++) {
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
                readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
                readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
            }
            return readIdx;
        }
        if (s == 0xB8) {
            short l = ub(buffer[++readIdx]);
            readIdx++;
            for (short i = 0; i < l; i++) {
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
                readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
                readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
            }
            return readIdx;
        }

        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        return readIdx;
    }

    /**
     * Consumes a CBOR map and locates the element having string key "id". After call, temp variables
     * representing the index and length of the matched value are set, if a match was found.
     *
     * @param apdu Request/response object
     * @param buffer Buffer containing incoming request
     * @param readIdx Current index into the request buffer
     * @param lc Length of the request buffer, as sent by the platform
     * @param byteString If true, ID should be a byte string: if false, a UTF-8 string
     * @param checkTypePublicKey If true, check there is a "type" key with value UTF-8 string "public-key"
     *
     * @return New index into the request buffer, after consuming one CBOR map element
     */
    private short consumeMapAndGetID(APDU apdu, byte[] buffer, short readIdx, short lc, boolean byteString,
                                     boolean checkTypePublicKey) {
        boolean foundId = false;
        boolean foundType = false;
        boolean correctType = false;
        transientStorage.readyStoredVars();
        short mapDef = ub(buffer[readIdx++]);
        if (readIdx >= lc) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }
        short mapEntryCount = 0;
        if ((mapDef & 0xF0) == 0xA0) {
            mapEntryCount = (short) (mapDef & 0x0F);
        } else if ((mapDef & 0xF0) == 0xB0 && mapDef < ub((byte) 0xB8)) {
            mapEntryCount = (short) ((mapDef & 0x0F) + 16);
        } else if (mapDef == (byte) 0xB8) {
            mapEntryCount = ub(buffer[readIdx++]);
            if (readIdx >= lc) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }
        } else {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }

        for (short i = 0; i < mapEntryCount; i++) {
            short keyDef = ub(buffer[readIdx++]);
            short keyLen = 0;
            if (readIdx >= lc) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }

            if (keyDef == 0x78) {
                keyLen = ub(buffer[readIdx++]);
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
            } else if (keyDef >= 0x60 && keyDef < 0x78) {
                keyLen = (short)(keyDef - 0x60);
            } else {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }

            boolean isId = (keyLen == 2 && buffer[readIdx] == 'i' && buffer[(short)(readIdx+1)] == 'd');
            if (isId && foundId) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }
            boolean isType = (keyLen == 4 && buffer[readIdx] == 't' && buffer[(short)(readIdx+1)] == 'y'
                && buffer[(short)(readIdx+2)] == 'p' && buffer[(short)(readIdx+3)] == 'e');
            if (isType && foundType) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }
            readIdx += keyLen;
            if (readIdx >= lc) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }

            short valDef = buffer[readIdx++];
            if (readIdx >= lc) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }
            short idPos = readIdx;

            short valLen = 0;
            if (valDef == 0x78 || valDef == 0x58) {
                if (isId) {
                    if (valDef == 0x78 && byteString) {
                        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                    } else if (valDef == 0x58 && !byteString) {
                        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                    }
                }
                if (isType && valDef == 0x58) {
                    // heh, literally "unexpected type" here
                    // don't throw an exception, since the spec allows all sorts of "types", we just ignore them
                    foundType = true;
                    correctType = false;
                }
                valLen = ub(buffer[readIdx++]);
                if (isId) {
                    idPos++;
                }
                if (readIdx >= lc) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
                }
            } else if (valDef >= 0x60 && valDef < 0x78) {
                if (isId && byteString) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
                valLen = (short) (valDef - 0x60);
            } else if (valDef >= 0x40 && valDef < 0x58) {
                if (isId && !byteString) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
                if (isType) {
                    // byte strings aren't valid for public-key types...
                    foundType = true;
                    correctType = false;
                }
                valLen = (short) (valDef - 0x40);
            } else {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }

            if (isId) {
                foundId = true;
                transientStorage.setStoredVars(idPos, valLen);
            }

            if (!foundType && isType && checkTypePublicKey) {
                foundType = true;
                correctType = valLen == (short) CannedCBOR.PUBLIC_KEY_TYPE.length
                  && Util.arrayCompare(buffer, readIdx,
                        CannedCBOR.PUBLIC_KEY_TYPE, (short) 0, valLen) == 0;
            }

            readIdx += valLen;
            if (readIdx >= lc) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }
        }

        if (!foundId) {
            sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_PARAMETER);
        }

        if (checkTypePublicKey) {
            if (!foundType) {
                // entirely missing "type" field
                sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_PARAMETER);
            }

            if (!correctType) {
                // We found an ID entry, but it's not of type "public-key" - treat it as if we found nothing
                transientStorage.readyStoredVars();
            }
        }


        return readIdx;
    }

    /**
     * Called by the platform when a new APDU comes in - entry point for the applet, really
     *
     * @param apdu Request/response object
     * @throws ISOException If an error occurs outside cases representable by CTAP2 error responses
     */
    @Override
    public void process(APDU apdu) throws ISOException {
        if (selectingApplet()) {
            // For U2F compatibility, the CTAP2 standard requires that we respond to select() as if we were a U2F
            // authenticator, and then let the platform figure out we're really CTAP2 by making a getAuthenticatorInfo
            // API request afterwards
            // sendByteArray(apdu, U2F_V2_RESPONSE);

            // ... but we DON'T implement U2F, so we can send the CTAP2-only response type
            sendByteArray(apdu, CannedCBOR.FIDO_2_RESPONSE);

            return;
        }

        byte[] apduBytes = apdu.getBuffer();

        byte cla = apduBytes[ISO7816.OFFSET_CLA];
        byte ins = apduBytes[ISO7816.OFFSET_INS];

        if (cla == (byte) 0x80 && ins == 0x12 && apduBytes[ISO7816.OFFSET_P1] == 0x01
                && apduBytes[ISO7816.OFFSET_P2] == 0x00) {
            // Explicit disable command (NFCCTAP_CONTROL end CTAP_MSG). Turn off, and stay off.
            transientStorage.disableAuthenticator();
        }

        if (transientStorage.authenticatorDisabled()) {
            return;
        }

        initKeyAgreementKeyIfNecessary();

        if ((cla == 0x00 || cla == (byte) 0x80)
                && ins == (byte) 0xC0) {
            // continue outgoing response from buffer
            if (transientStorage.getOutgoingContinuationRemaining() == 0) {
                throwException(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
            }

            short outgoingOffset = transientStorage.getOutgoingContinuationOffset();
            short outgoingRemaining = transientStorage.getOutgoingContinuationRemaining();

            short writeSize = 256 <= outgoingRemaining ? 256 : outgoingRemaining;
            apdu.setOutgoing();
            apdu.setOutgoingLength(writeSize);
            Util.arrayCopyNonAtomic(bufferMem, outgoingOffset,
                    apdu.getBuffer(), (short) 0, writeSize);
            apdu.sendBytes((short) 0, writeSize);
            outgoingOffset += writeSize;
            outgoingRemaining -= writeSize;
            transientStorage.setOutgoingContinuation(outgoingOffset, outgoingRemaining);
            if (outgoingRemaining >= 256) {
                throwException(ISO7816.SW_BYTES_REMAINING_00);
            } else if (outgoingRemaining > 0) {
                throwException((short) (ISO7816.SW_BYTES_REMAINING_00 + outgoingRemaining));
            }
            transientStorage.clearOutgoingContinuation();
            return;
        } else {
            transientStorage.clearOutgoingContinuation();
        }

        if (apdu.isCommandChainingCLA()) {
            // Incoming chained request
            short amtRead = apdu.setIncomingAndReceive();

            short lc = apdu.getIncomingLength();
            if (lc == 0) {
                ISOException.throwIt(ISO7816.SW_DATA_INVALID);
            }
            fullyReadReq(apdu, apduBytes, lc, amtRead, true);

            transientStorage.increaseChainIncomingReadOffset(lc);
            transientStorage.setStoredCommandByteIfNone(apduBytes[apdu.getOffsetCdata()]);
            return;
        }

        if (apduBytes[ISO7816.OFFSET_CLA] != (byte) 0x80) {
            throwException(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        if (apduBytes[ISO7816.OFFSET_INS] != 0x10) {
            throwException(ISO7816.SW_INS_NOT_SUPPORTED);
        }

        if ((apduBytes[ISO7816.OFFSET_P1] != 0x00 && apduBytes[ISO7816.OFFSET_P1] != (byte) 0x80) || apduBytes[ISO7816.OFFSET_P2] != 0x00) {
            throwException(ISO7816.SW_INCORRECT_P1P2);
        }

        short amtRead = apdu.setIncomingAndReceive();
        short lc = apdu.getIncomingLength();

        if (amtRead == 0) {
            throwException(ISO7816.SW_DATA_INVALID);
        }

        short incomingOffset = 1;
        short lcEffective = (short)(lc + 1);
        byte cmdByte = apduBytes[apdu.getOffsetCdata()];
        short chainingReadOffset = transientStorage.getChainIncomingReadOffset();
        if (chainingReadOffset > 0) {
            cmdByte = transientStorage.getStoredCommandByte();
            lcEffective += chainingReadOffset;
            transientStorage.clearStoredCommandByte();
        }

        if (cmdByte != FIDOConstants.CMD_CREDENTIAL_MANAGEMENT
            && cmdByte != FIDOConstants.CMD_CREDENTIAL_MANAGEMENT_PREVIEW) {
            transientStorage.clearIterationPointers();
        }

        if (cmdByte != FIDOConstants.CMD_GET_NEXT_ASSERTION) {
            transientStorage.clearAssertIterationPointer();
        }

        byte[] reqBuffer;

        switch (cmdByte) {
            case FIDOConstants.CMD_MAKE_CREDENTIAL:
                reqBuffer = fullyReadReq(apdu, apduBytes, lc, amtRead, false);

                makeCredential(apdu, lcEffective, reqBuffer, incomingOffset);
                break;
            case FIDOConstants.CMD_GET_INFO:
                sendAuthInfo(apdu);
                break;
            case FIDOConstants.CMD_GET_ASSERTION:
                // getAssertion extensively uses the APDU buffer, so needs incoming requests to be buffered
                reqBuffer = fullyReadReq(apdu, apduBytes, lc, amtRead, true);

                getAssertion(apdu, lcEffective, reqBuffer, incomingOffset, (short) 0);
                break;
            case FIDOConstants.CMD_GET_NEXT_ASSERTION:
                if (transientStorage.getAssertIterationPointer() <= 0) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_NOT_ALLOWED);
                }
                // Note: no fullyReadReq here, because we already put the meaningful input parameters into scratch

                // We set lc to a high value here because we ALREADY successfully processed the request once,
                // back when it was a normal getAssertion call. We know it's valid... enough.
                getAssertion(apdu, (short) 16000, null, incomingOffset, transientStorage.getAssertIterationPointer());
                break;
            case FIDOConstants.CMD_CLIENT_PIN:
                reqBuffer = fullyReadReq(apdu, apduBytes, lc, amtRead, false);

                clientPINSubcommand(apdu, reqBuffer, incomingOffset, lcEffective);
                break;
            case FIDOConstants.CMD_RESET:
                authenticatorReset(apdu);
                break;
            case FIDOConstants.CMD_CREDENTIAL_MANAGEMENT: // intentional fallthrough, for backwards compat
            case FIDOConstants.CMD_CREDENTIAL_MANAGEMENT_PREVIEW:
                reqBuffer = fullyReadReq(apdu, apduBytes, lc, amtRead, false);

                credManagementSubcommand(apdu, reqBuffer, lcEffective, incomingOffset);
                break;
            case FIDOConstants.CMD_AUTHENTICATOR_SELECTION:
                authenticatorSelection(apdu);
                break;
            default:
                sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_COMMAND);
                break;
        }

        transientStorage.resetChainIncomingReadOffset();
    }

    /**
     * Handles an authenticatorSelection FIDO2 command
     *
     * @param apdu Request/response object
     */
    private void authenticatorSelection(APDU apdu) {
        // Presence not really implemented - user always considered present
        byte[] buffer = apdu.getBuffer();

        buffer[0] = FIDOConstants.CTAP2_OK;

        sendNoCopy(apdu, (short) 0, (short) 1);
    }

    /**
     * Handler to consume and dispatch subcommands for the credential management optional feature
     *
     * @param apdu Request/response object
     * @param buffer Buffer containing incoming request
     * @param lc Length of incoming request, as sent by the platform
     * @param readIdx Read index into bufferMem
     */
    private void credManagementSubcommand(APDU apdu, byte[] buffer, short lc, short readIdx) {
        if (resetRequested) {
            resetRequested = false;
        }

        if (!pinSet) {
            // All credential management commands require and validate a PIN
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_NOT_SET);
        }

        if (lc == 0) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        if ((buffer[readIdx] & 0xF0) != 0xA0) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }

        short numOptions = (short) (buffer[readIdx++] & 0x0F);

        if (buffer[readIdx++] != 0x01) { // map key: subCommand
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }
        short subCommandIdx = readIdx;
        short subCommandNumber = ub(buffer[readIdx++]);
        if (subCommandNumber > 23) {
            // This will likely never be legal...
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }

        short subCommandParamsIdx = -1;
        short subCommandParamsLen = 0;
        if (buffer[readIdx] == 0x02) { // map key: subCommandParams
            subCommandParamsIdx = ++readIdx;
            readIdx = consumeAnyEntity(apdu, buffer, readIdx, lc);
            subCommandParamsLen = (short)(readIdx - subCommandParamsIdx);
        }

        if (subCommandNumber != FIDOConstants.CRED_MGMT_ENUMERATE_RPS_NEXT &&
            subCommandNumber != FIDOConstants.CRED_MGMT_ENUMERATE_CREDS_NEXT) {
            // Don't ask me why these commands don't need the PIN token...
            if (buffer[readIdx++] != 0x03) { // map key: pinUvAuthProtocol
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
            }
            byte pinProtocol = buffer[readIdx++];
            checkPinProtocolSupported(apdu, pinProtocol);

            if (buffer[readIdx++] != 0x04) { // map key: pinUvAuthParam
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
            }
            if (pinProtocol == 1) {
                if (buffer[readIdx++] != 0x50) { // byte array, 16 bytes long
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
            } else {
                if (buffer[readIdx++] != 0x58) { // byte array, one-byte length
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
                if (buffer[readIdx++] != 0x20) { // 32 bytes long
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
            }

            // Check PIN token
            if (subCommandParamsLen > 189) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_REQUEST_TOO_LARGE);
            }
            if (subCommandParamsIdx != -1) {
                // Straight-up mangle the input buffer to put the command byte immediately before the subcommand params
                buffer[(short)(subCommandParamsIdx - 1)] = buffer[subCommandIdx];
                checkPinToken(apdu, buffer, (short) (subCommandParamsIdx - 1), (short) (subCommandParamsLen + 1),
                        buffer, readIdx, pinProtocol, false);
            } else {
                checkPinToken(apdu, buffer, subCommandIdx, (short) 1,
                        buffer, readIdx, pinProtocol, false);
            }
            if ((transientStorage.getPinPermissions() & FIDOConstants.PERM_CRED_MANAGEMENT) == 0x00) {
                // PIN token doesn't have appropriate permissions for credential management operations
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
            }
        }

        switch (subCommandNumber) {
            case FIDOConstants.CRED_MGMT_GET_CREDS_META:
                handleCredentialManagementGetCredsMetadata(apdu);
                break;
            case FIDOConstants.CRED_MGMT_ENUMERATE_RPS_BEGIN:
                handleEnumerateRPs(apdu, (short) 0);
                break;
            case FIDOConstants.CRED_MGMT_ENUMERATE_RPS_NEXT:
                short rpPtr = transientStorage.getRPIterationPointer();
                if (rpPtr == 0) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_OPERATION_DENIED);
                }
                handleEnumerateRPs(apdu, rpPtr);
                break;
            case FIDOConstants.CRED_MGMT_ENUMERATE_CREDS_NEXT:
                short credPtr = transientStorage.getCredIterationPointer();
                if (credPtr == 0) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_OPERATION_DENIED);
                }
                handleEnumerateCreds(apdu, buffer, (short) -1, credPtr, lc);
                break;
            case FIDOConstants.CRED_MGMT_ENUMERATE_CREDS_BEGIN:
                handleEnumerateCreds(apdu, buffer, subCommandParamsIdx, (short) 0, lc);
                break;
            case FIDOConstants.CRED_MGMT_DELETE_CRED:
                handleDeleteCred(apdu, buffer, subCommandParamsIdx, lc);
                break;
            case FIDOConstants.CRED_MGMT_UPDATE_USER_INFO:
                handleUserUpdate(apdu, buffer, subCommandParamsIdx, lc);
                break;
            default:
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_UNSUPPORTED_OPTION);
                break;
        }
    }

    /**
     * Handles a CTAP credential management user update subcommand
     *
     * @param apdu Request/response object
     * @param buffer Buffer containing incoming request
     * @param readOffset Index of subcommand parameters in read buffer
     * @param lc Length of incoming request, as sent by the platform
     */
    private void handleUserUpdate(APDU apdu, byte[] buffer, short readOffset, short lc) {
        if (buffer[readOffset++] != (byte) 0xA2) { // map with two entries
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }
        if (buffer[readOffset++] != 0x02) { // map key: credentialId
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        readOffset = consumeMapAndGetID(apdu, buffer, readOffset, lc, true, true);
        short credIdIdx = transientStorage.getStoredIdx();
        short credIdLen = transientStorage.getStoredLen();
        if (credIdLen != CREDENTIAL_ID_LEN) {
            // Not our credential - can't match anything
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_NO_CREDENTIALS);
        }

        if (buffer[readOffset++] != 0x03) { // map key: user
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        readOffset = consumeMapAndGetID(apdu, buffer, readOffset, lc, true, false);
        short userIdIdx = transientStorage.getStoredIdx();
        short userIdLen = transientStorage.getStoredLen();
        if (userIdLen > MAX_USER_ID_LENGTH) {
            // We can't store user IDs this long, so we won't have one stored...
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_NO_CREDENTIALS);
        }

        boolean foundHit = false;
        short scannedRKs = 0;
        short uidOffset = scratchAlloc(MAX_USER_ID_LENGTH);
        for (short i = 0; i < NUM_RESIDENT_KEY_SLOTS; i++) {
            if (!residentKeyValidity[i]) {
                continue;
            }
            if (residentKeyUserIdLengths[i] != userIdLen) {
                // Can't possibly match
                continue;
            }

            // Don't need to decrypt creds, just byte-compare them
            if (Util.arrayCompare(residentKeyData, (short)(i * CREDENTIAL_ID_LEN),
                    buffer, credIdIdx, CREDENTIAL_ID_LEN) == 0) {
                // Matching cred.
                // We need to extract the credential to check that our PIN token WOULD have permission
                short scratchExtractedCred = scratchAlloc(CREDENTIAL_ID_LEN);
                symmetricUnwrapper.doFinal(residentKeyData, (short)(i * CREDENTIAL_ID_LEN), CREDENTIAL_ID_LEN,
                        scratch, scratchExtractedCred
                );
                if (permissionsRpId[0] != 0x00) {
                    if (Util.arrayCompare(scratch, (short)(scratchExtractedCred + 32),
                            permissionsRpId, (short) 1, RP_HASH_LEN) != 0) {
                        // permissions RP ID in use, but doesn't match RP of this credential
                        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
                    }
                }

                // Now that we have permission, check the user ID
                symmetricUnwrapper.doFinal(residentKeyUserIds, (short)(i * MAX_USER_ID_LENGTH), MAX_USER_ID_LENGTH,
                        scratch, uidOffset);
                if (Util.arrayCompare(scratch, uidOffset,
                        buffer, userIdIdx, userIdLen) == 0) {
                    // Matches both credential and user ID - it's a hit.
                    // No actual updating work to do here because we don't store anything other than the ID

                    foundHit = true;
                    break;
                } else {
                    // matches credential ID, but doesn't match user ID
                    sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_PARAMETER);
                }
            }

            if (++scannedRKs == numResidentCredentials) {
                // No more RKs...
                break;
            }
        }
        scratchRelease(MAX_USER_ID_LENGTH);

        if (!foundHit) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_NO_CREDENTIALS);
        }

        byte[] outBuf = apdu.getBuffer();
        outBuf[0] = FIDOConstants.CTAP2_OK;
        sendNoCopy(apdu, (short) 0, (short) 1);
    }

    /**
     * Deletes a resident key by its credential ID blob, and updates bookkeeping state to match
     *
     * @param apdu Request/response object
     * @param buffer Buffer containing incoming request
     * @param readOffset Read index into bufferMem
     * @param lc Length of incoming request, as sent by the platform
     */
    private void handleDeleteCred(APDU apdu, byte[] buffer, short readOffset, short lc) {
        transientStorage.clearIterationPointers();

        if (buffer[readOffset++] != (byte) 0xA1) { // map with one entry
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }
        if (buffer[readOffset++] != 0x02) { // map key: credentialId
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        readOffset = consumeMapAndGetID(apdu, buffer, readOffset, lc, true, true);
        short credIdIdx = transientStorage.getStoredIdx();
        short credIdLen = transientStorage.getStoredLen();

        if (credIdLen != CREDENTIAL_ID_LEN) {
            // We're not gonna have credentials of random lengths on here...
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_NO_CREDENTIALS);
        }

        short scannedRKs = 0;
        for (short i = 0; i < NUM_RESIDENT_KEY_SLOTS; i++) {
            if (!residentKeyValidity[i]) {
                continue;
            }

            // Compare still encrypted, which is fine
            if (Util.arrayCompare(residentKeyData, (short)(i * CREDENTIAL_ID_LEN),
                    buffer, credIdIdx, CREDENTIAL_ID_LEN) == 0) {
                // Found a match! Unfortunately, we need to unpack the credential to check if this PIN token
                // has permission to delete it...

                // Unpack right into the output buffer, potentially overwriting the encrypted data
                // (if it's the input buffer)... but we no longer need it
                byte[] outBuf = apdu.getBuffer();
                symmetricUnwrapper.doFinal(
                        buffer, credIdIdx, CREDENTIAL_ID_LEN,
                        outBuf, (short) 0
                );
                short rpIdHashIdx = (short) 32;

                if (permissionsRpId[0] != 0x00) {
                    if (Util.arrayCompare(outBuf, rpIdHashIdx,
                            permissionsRpId, (short) 1, RP_HASH_LEN) != 0) {
                        // permissions RP ID in use, but doesn't match RP of deleteCred operation
                        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
                    }
                }

                if (residentKeyUniqueRP[i]) {
                    // Due to how we manage RP validity, we need to find ANOTHER RK with the same RP,
                    // to set residentKeyUniqueRP on it and thus "promote" it to being the representative
                    // of the RP for iteration purposes
                    short rpHavingSameRP = -1;

                    // Unpack the other cred into the upper half of the output buffer, which we're not using
                    short unpackedSecondCredIdx = (short) 128;

                    for (short otherRKIdx = 0; otherRKIdx < NUM_RESIDENT_KEY_SLOTS; otherRKIdx++) {
                        if (otherRKIdx == i) {
                            // we want ANOTHER RK, not this one...
                            continue;
                        }
                        if (!residentKeyValidity[otherRKIdx]) {
                            // deleted keys need not apply
                            continue;
                        }
                        if (checkCredential(residentKeyData, (short)(otherRKIdx * CREDENTIAL_ID_LEN), CREDENTIAL_ID_LEN,
                                outBuf, rpIdHashIdx,
                                outBuf, unpackedSecondCredIdx)) {
                            // match. this is our promotion candidate!
                            rpHavingSameRP = otherRKIdx;
                            break;
                        }
                    }

                    JCSystem.beginTransaction();
                    boolean ok = false;
                    try {
                        if (rpHavingSameRP == -1) {
                            // We couldn't find anybody else that shared our RP, which means deleting us
                            // also lowered the total RP count by one
                            numResidentRPs--;
                        } else {
                            residentKeyUniqueRP[rpHavingSameRP] = true;
                        }
                        residentKeyValidity[i] = false;
                        numResidentCredentials--;
                        ok = true;
                    } finally {
                        if (ok) {
                            JCSystem.commitTransaction();
                        } else {
                            JCSystem.abortTransaction();
                        }
                    }
                } else {
                    JCSystem.beginTransaction();
                    boolean ok = false;
                    try {
                        residentKeyValidity[i] = false;
                        numResidentCredentials--;
                        ok = true;
                    } finally {
                        if (ok) {
                            JCSystem.commitTransaction();
                        } else {
                            JCSystem.abortTransaction();
                        }
                    }
                }

                outBuf[0] = FIDOConstants.CTAP2_OK;
                sendNoCopy(apdu, (short) 0, (short) 1);
                return;
            }

            if (++scannedRKs == numResidentCredentials) {
                // No more RKs...
                break;
            }
        }

        // If we got here, we didn't find a matching credential
        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_NO_CREDENTIALS);
    }

    /**
     * Enumerates creds
     *
     * @param apdu Request/response object
     * @param buffer Buffer containing incoming request
     * @param bufferIdx Read index into bufferMem
     * @param startCredIdx Offset of the first credential to consider, in the resident key slots.
     *                     If zero, we're starting a new iteration
     * @param lc Length of the incoming request, as sent by the platform
     */
    private void handleEnumerateCreds(APDU apdu, byte[] buffer, short bufferIdx, short startCredIdx, short lc) {
        transientStorage.clearIterationPointers();

        if (startCredIdx > NUM_RESIDENT_KEY_SLOTS) { // intentional > instead of >=
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_NO_CREDENTIALS);
        }

        byte[] rpIdHashBuf;
        short rpIdHashIdx;
        if (startCredIdx == 0) {
            // Iteration start: read RP ID hash from request buffer
            if (buffer[bufferIdx++] != (byte) 0xA1) { // map, one entry
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
            if (buffer[bufferIdx++] != 0x01) { // map key: rpIdHash
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
            }
            if (buffer[bufferIdx++] != 0x58 || buffer[bufferIdx++] != RP_HASH_LEN) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
            rpIdHashBuf = buffer;
            rpIdHashIdx = bufferIdx;

            if ((short)(bufferIdx + RP_HASH_LEN) >= lc) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
            }

            if (permissionsRpId[0] != 0x00) {
                // A permissions RP ID is set: verify it matches the RP for which we're iterating creds
                if (Util.arrayCompare(rpIdHashBuf, rpIdHashIdx,
                        permissionsRpId, (short) 1, RP_HASH_LEN) != 0) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
                }
            }
        } else {
            // Continuing iteration, we get the RP ID hash from the previous credential
            rpIdHashBuf = apdu.getBuffer();
            symmetricUnwrapper.doFinal(
                    residentKeyData, (short) ((startCredIdx - 1) * CREDENTIAL_ID_LEN), CREDENTIAL_ID_LEN,
                    rpIdHashBuf, (short) 0
            );
            rpIdHashIdx = (short) 32; // RP ID hash is the second half of the unwrapped credential
        }

        byte[] outBuf = apdu.getBuffer();

        short scannedRKs = 0;
        short rkIndex;
        for (rkIndex = startCredIdx; rkIndex < NUM_RESIDENT_KEY_SLOTS; rkIndex++) {
            if (!residentKeyValidity[rkIndex]) {
                continue;
            }

            // Use upper half of request buffer as temporary storage: incoming request is very short...
            if (checkCredential(
                    residentKeyData, (short) (rkIndex * CREDENTIAL_ID_LEN), CREDENTIAL_ID_LEN,
                    rpIdHashBuf, rpIdHashIdx,
                    outBuf, (short) 128)) {
                // Cred is for this RP ID, yay.

                byte matchingCount = 1; // remember to count THIS cred as a match
                if (startCredIdx == 0) {
                    // Unfortunately, we need to scan forward through all remaining credentials
                    // we're not storing a list of which creds share an RP, so this is the only way to get
                    // the count associated with this RP...
                    for (short otherCredIdx = (short) (rkIndex + 1); otherCredIdx < NUM_RESIDENT_KEY_SLOTS; otherCredIdx++) {
                        if (!residentKeyValidity[otherCredIdx]) {
                            continue;
                        }

                        if (checkCredential(
                                residentKeyData, (short)(otherCredIdx * CREDENTIAL_ID_LEN), CREDENTIAL_ID_LEN,
                                rpIdHashBuf, rpIdHashIdx,
                                outBuf, (short) 128 // okay to clobber decrypted data from other cred; it's unused
                        )) {
                            matchingCount++;
                        }
                    }

                    scratchRelease(CREDENTIAL_ID_LEN);
                }
                transientStorage.setCredIterationPointer((short)(rkIndex + 1)); // resume iteration from beyond this one

                short writeOffset = 0;

                outBuf[writeOffset++] = FIDOConstants.CTAP2_OK;
                outBuf[writeOffset++] = startCredIdx == 0 ? (byte) 0xA5 : (byte) 0xA4; // map, four or five entries
                outBuf[writeOffset++] = 0x06; // map key: pubKeyCredentialsUserEntry
                writeOffset = Util.arrayCopyNonAtomic(CannedCBOR.SINGLE_ID_MAP_PREAMBLE, (short) 0,
                        outBuf, writeOffset, (short) CannedCBOR.SINGLE_ID_MAP_PREAMBLE.length);
                short userIdLength = ub(residentKeyUserIdLengths[rkIndex]);
                writeOffset = encodeIntLenTo(outBuf, writeOffset, userIdLength, true);

                // The user ID needs to be fully decrypted (MAX_USER_ID_LENGTH bytes)
                symmetricUnwrapper.doFinal(residentKeyUserIds, (short)(MAX_USER_ID_LENGTH * rkIndex), MAX_USER_ID_LENGTH,
                        outBuf, writeOffset);
                // ... but we only advance the write offset by however many bytes of it are really valid
                writeOffset += userIdLength;

                outBuf[writeOffset++] = 0x07; // map key: credentialId
                writeOffset = packCredentialId(residentKeyData, (short)(CREDENTIAL_ID_LEN * rkIndex),
                        outBuf, writeOffset);

                outBuf[writeOffset++] = 0x08; // map key: publicKey
                writeOffset = Util.arrayCopyNonAtomic(CannedCBOR.PUBLIC_KEY_ALG_PREAMBLE, (short) 0,
                        outBuf, writeOffset, (short) CannedCBOR.PUBLIC_KEY_ALG_PREAMBLE.length);
                // We've written a bit over 128 bytes now (<=64 user, 64 credential, <32 CBOR)
                // it's safe for us to use the last 64 bytes of the APDU buffer to unpack the public key
                symmetricUnwrapper.doFinal(residentKeyPublicKeys, (short)(rkIndex * KEY_POINT_LENGTH * 2), (short)(KEY_POINT_LENGTH * 2),
                        outBuf, (short) 192);
                writeOffset = writePubKey(outBuf, writeOffset, outBuf, (short) 192);

                if (startCredIdx == 0) {
                    outBuf[writeOffset++] = 0x09; // map key: totalCredentials
                    writeOffset = encodeIntTo(outBuf, writeOffset, matchingCount);
                }

                outBuf[writeOffset++] = 0x0A; // map key: credProtect
                outBuf[writeOffset++] = 0x03; // cred protect level 3

                sendNoCopy(apdu, (short) 0, writeOffset);
                return;
            }

            if (++scannedRKs == numResidentCredentials) {
                // No more RKs...
                break;
            }
        }

        // If we fall through to here, we didn't find a cred
        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_NO_CREDENTIALS);
    }

    /**
     * Pack a credential ID (CBOR-wrapped) into a target buffer
     *
     * @param credBuffer Buffer containing credential ID
     * @param credOffset Offset of credential ID in input buffer
     * @param writeBuffer Output buffer into which to write CBOR
     * @param writeOffset Write index into output buffer
     *
     * @return New write index into output buffer, after writing credential CBOR
     */
    private short packCredentialId(byte[] credBuffer, short credOffset, byte[] writeBuffer, short writeOffset) {
        writeBuffer[writeOffset++] = (byte) 0xA2; // map: two entries

        writeBuffer[writeOffset++] = 0x62; // string - two bytes long
        writeBuffer[writeOffset++] = 0x69; // i
        writeBuffer[writeOffset++] = 0x64; // d
        writeOffset = encodeIntLenTo(writeBuffer, writeOffset, CREDENTIAL_ID_LEN, true);
        writeOffset = Util.arrayCopyNonAtomic(credBuffer, credOffset,
                writeBuffer, writeOffset, CREDENTIAL_ID_LEN);

        writeBuffer[writeOffset++] = 0x64; // string - four bytes long
        writeBuffer[writeOffset++] = 0x74; // t
        writeBuffer[writeOffset++] = 0x79; // y
        writeBuffer[writeOffset++] = 0x70; // p
        writeBuffer[writeOffset++] = 0x65; // e
        writeOffset = encodeIntLenTo(writeBuffer, writeOffset, (short) CannedCBOR.PUBLIC_KEY_TYPE.length, false);
        writeOffset = Util.arrayCopyNonAtomic(CannedCBOR.PUBLIC_KEY_TYPE, (short) 0,
                writeBuffer, writeOffset, (short) CannedCBOR.PUBLIC_KEY_TYPE.length);

        return writeOffset;
    }

    /**
     * Handles enumerating stored RPs on the authenticator
     *
     * @param apdu Request/response offset
     * @param startOffset The index of the next RK which has a "unique" RP
     */
    private void handleEnumerateRPs(APDU apdu, short startOffset) {
        if (permissionsRpId[0] != 0x00) {
            // Standard requires that a PIN token *not* be bound to an RP ID for this subcommand
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
        }

        // if anything goes wrong, iteration will need to be restarted
        transientStorage.clearIterationPointers();

        short scannedRKs = 0;
        short rkIndex;
        for (rkIndex = startOffset; rkIndex < NUM_RESIDENT_KEY_SLOTS; rkIndex++) {
            // if a credential is not for a *unique* RP, ignore it - we're enumerating RPs here!
            if (residentKeyValidity[rkIndex] && residentKeyUniqueRP[rkIndex]) {
                break;
            }
            if (++scannedRKs == numResidentCredentials) {
                // No more RKs...
                rkIndex = NUM_RESIDENT_KEY_SLOTS;
                break;
            }
        }

        if (rkIndex >= NUM_RESIDENT_KEY_SLOTS) {
            // Iterated too far, or called with no stored creds...
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_NO_CREDENTIALS);
        }

        boolean isContinuation = startOffset > 0;

        short writeOffset = 0;

        byte[] outBuf = apdu.getBuffer();

        outBuf[writeOffset++] = FIDOConstants.CTAP2_OK;

        transientStorage.setRPIterationPointer((short)(rkIndex + 1));

        outBuf[writeOffset++] = isContinuation ? (byte) 0xA2 : (byte) 0xA3; // map with two or three keys
        outBuf[writeOffset++] = 0x03; // map key: rp
        writeOffset = Util.arrayCopyNonAtomic(CannedCBOR.SINGLE_ID_MAP_PREAMBLE, (short) 0,
                outBuf, writeOffset, (short) CannedCBOR.SINGLE_ID_MAP_PREAMBLE.length);
        byte rpIdLength = residentKeyRPIdLengths[rkIndex];
        writeOffset = encodeIntLenTo(outBuf, writeOffset, rpIdLength, false);

        // Decrypt the full RP ID, ...
        short numBytesUnwrapped = symmetricUnwrapper.doFinal(residentKeyRPIds, (short) (MAX_RESIDENT_RP_ID_LENGTH * rkIndex), MAX_RESIDENT_RP_ID_LENGTH,
                outBuf, writeOffset);
        if (numBytesUnwrapped != MAX_RESIDENT_RP_ID_LENGTH) {
            throwException(ISO7816.SW_UNKNOWN);
        }
        // ... but only advance the write cursor by as much of it is valid
        writeOffset += rpIdLength;

        outBuf[writeOffset++] = 0x04; // map key: rpIdHash
        writeOffset = encodeIntLenTo(outBuf, writeOffset, RP_HASH_LEN, true);

        // Unwrap the given RK so we can return its decrypted RP hash
        numBytesUnwrapped = symmetricUnwrapper.doFinal(residentKeyData, (short) (CREDENTIAL_ID_LEN * rkIndex), CREDENTIAL_ID_LEN,
                outBuf, writeOffset);
        if (numBytesUnwrapped != CREDENTIAL_ID_LEN) {
            throwException(ISO7816.SW_UNKNOWN);
        }
        // VERY CAREFULLY slide what we just wrote 32 bytes down, overwriting the private key with the RP ID hash
        writeOffset = Util.arrayCopyNonAtomic(outBuf, (short)(writeOffset + 32),
                outBuf, writeOffset, RP_HASH_LEN);

        if (!isContinuation) {
            outBuf[writeOffset++] = 0x05; // map key: totalRPs
            writeOffset = encodeIntTo(outBuf, writeOffset, numResidentRPs);
        }

        sendNoCopy(apdu, (short) 0, writeOffset);
    }

    /**
     * Processes the CTAP2.1 credential management getCredsMetaData command
     *
     * @param apdu Request/response object
     */
    private void handleCredentialManagementGetCredsMetadata(APDU apdu) {
        if (permissionsRpId[0] != 0x00) {
            // Standard requires that a PIN token *not* be bound to an RP ID for this subcommand
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_INVALID);
        }

        short writeOffset = 0;

        byte[] outBuf = apdu.getBuffer();

        outBuf[writeOffset++] = FIDOConstants.CTAP2_OK;
        outBuf[writeOffset++] = (byte) 0xA2; // map: two items
        outBuf[writeOffset++] = 0x01; // map key: existingResidentCredentialsCount
        writeOffset = encodeIntTo(outBuf, writeOffset, numResidentCredentials);
        outBuf[writeOffset++] = 0x02; // map key: maxPossibleRemainingCredentialsCount
        short remainingCredentials = (short)(NUM_RESIDENT_KEY_SLOTS - numResidentCredentials);
        writeOffset = encodeIntTo(outBuf, writeOffset, (byte) remainingCredentials);

        sendNoCopy(apdu, (short) 0, writeOffset);
    }

    /**
     * Packs a low-valued integer as a CBOR value into bufferMem
     *
     * @param writeOffset Write offset into bufferMem
     * @param v Value to pack
     *
     * @return New write offset into bufferMem
     */
    private short encodeInt(short writeOffset, byte v) {
        return encodeIntTo(bufferMem, writeOffset, v);
    }

    /**
     * Packs a low-valued integer as a CBOR value into a given buffer
     *
     * @param outBuf Buffer into which to write
     * @param writeOffset Write offset into given buffer
     * @param v Value to pack
     *
     * @return New write offset into given buffer
     */
    private short encodeIntTo(byte[] outBuf, short writeOffset, byte v) {
        if (v < 24) {
            outBuf[writeOffset++] = v;
        } else {
            outBuf[writeOffset++] = 0x18; // Integer stored in one byte
            outBuf[writeOffset++] = v;
        }
        return writeOffset;
    }

    /**
     * Reset authenticator to factory fresh state
     *
     * @param apdu Request/response object
     */
    private void authenticatorReset(APDU apdu) {
        if (PROTECT_AGAINST_MALICIOUS_RESETS) {
            if (transientStorage.isResetCommandSentSincePoweron()) {
                // Already tried to reset once since power applied, and the protection feature is enabled.
                // Power off the token before trying again.
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_OPERATION_DENIED);
            }

            if (!resetRequested) {
                // Reject this request, to require confirmation
                transientStorage.setResetCommandSentSincePoweron();
                resetRequested = true;
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_OPERATION_DENIED);
            }
        }

        short pinIdx = pinRetryCounter.prepareIndex();

        JCSystem.beginTransaction();
        boolean ok = false;
        try {
            random.generateData(hmacWrapperBytes, (short) 0, (short) hmacWrapperBytes.length);

            for (short i = 0; i < NUM_RESIDENT_KEY_SLOTS; i++) {
                residentKeyValidity[i] = false;
            }
            numResidentCredentials = 0;
            numResidentRPs = 0;

            pinSet = false;
            pinRetryCounter.reset(pinIdx);

            random.generateData(pinKDFSalt, (short) 0, (short) pinKDFSalt.length);
            random.generateData(wrappingIV, (short) 0, (short) wrappingIV.length);

            resetWrappingKey();

            counter.clear();

            transientStorage.fullyReset();

            forceInitKeyAgreementKey();

            resetRequested = false;

            ok = true;
        } finally {
            if (ok) {
                JCSystem.commitTransaction();
                sendErrorByte(apdu, FIDOConstants.CTAP2_OK);
            } else {
                JCSystem.abortTransaction();
                throwException(ISO7816.SW_UNKNOWN);
            }
        }
    }

    /**
     * Resets the "wrapping key" to a random value. THIS INVALIDATES ALL ISSUED CREDENTIALS.
     *
     * After call, symmetric crypto is available with the new (random) key.
     */
    private void resetWrappingKey() {
        random.generateData(wrappingKeySpace, (short) 0, (short) wrappingKeySpace.length);
        wrappingKey.setKey(wrappingKeySpace, (short) 0);
        random.generateData(wrappingKeyValidation, (short) 0, (short) 32);

        // Put the HMAC-SHA256 of the first half of wrappingKeyValidation into the second half
        // We'll use this to validate we have the correct wrapping key
        hmacSha256(null, wrappingKeySpace, (short) 0,
                wrappingKeyValidation, (short) 0, (short) 32,
                wrappingKeyValidation, (short) 32, false);

        initSymmetricCrypto();
    }

    /**
     * Replies to the FIDO2 authenticator info command
     *
     * @param apdu Request/response object
     */
    private void sendAuthInfo(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        short offset = 0;

        offset = Util.arrayCopyNonAtomic(CannedCBOR.AUTH_INFO_RESPONSE, (short) 0,
                buffer, offset, (short) CannedCBOR.AUTH_INFO_RESPONSE.length);

        if (pinSet) {
            buffer[offset++] = (byte) 0xF5; // true
        } else {
            buffer[offset++] = (byte) 0xF4; // false
        }

        offset = encodeIntLenTo(buffer, offset, (short) CannedCBOR.PIN_UV_AUTH_TOKEN.length, false);
        offset = Util.arrayCopyNonAtomic(CannedCBOR.PIN_UV_AUTH_TOKEN, (short) 0,
                buffer, offset, (short) CannedCBOR.PIN_UV_AUTH_TOKEN.length);
        buffer[offset++] = (byte) 0xF5; // true

        offset = encodeIntLenTo(buffer, offset, (short) CannedCBOR.MAKE_CRED_UV_NOT_REQD.length, false);
        offset = Util.arrayCopyNonAtomic(CannedCBOR.MAKE_CRED_UV_NOT_REQD, (short) 0,
                buffer, offset, (short) CannedCBOR.MAKE_CRED_UV_NOT_REQD.length);
        buffer[offset++] = (byte) 0xF4; // false

        buffer[offset++] = 0x06; // map key: pinProtocols
        buffer[offset++] = (byte) 0x82; // array: two items
        buffer[offset++] = 0x01; // pin protocol version 1
        buffer[offset++] = 0x02; // pin protocol version 2

        buffer[offset++] = 0x07; // map key: maxCredentialCountInList
        buffer[offset++] = 0x0A; // ten

        buffer[offset++] = 0x08; // map key: maxCredentialIdLength
        buffer[offset++] = 0x18; // one-byte integer
        buffer[offset++] = 0x40; // sixty-four

        buffer[offset++] = 0x0E; // map key: firmwareVersion
        buffer[offset++] = 0x01; // one

        buffer[offset++] = 0x14; // map key: remainingDiscoverableCredentials
        offset = encodeIntTo(buffer, offset, (byte)(NUM_RESIDENT_KEY_SLOTS - numResidentCredentials));

        sendNoCopy(apdu, (short) 0, offset);
    }

    /**
     * Aborts processing and sends a particular status code to the platform.
     * Also releases any scratch memory and resets APDU chain handling state
     *
     * @param swCode Two-byte status code - may be SW_NO_ERROR if desired
     */
    private void throwException(short swCode) {
        throwException(swCode, true);
    }

    /**
     * As throwException, but optionally preserves iteration state
     *
     * @param swCode Two-byte status code - possibly SW_NO_ERROR
     * @param clearIteration If true, clears iteration state variables as per an exception. If false, leaves them,
     *                       allowing iteration calls like enumerateNextRP despite the "exception"
     */
    private void throwException(short swCode, boolean clearIteration) {
        if (clearIteration) {
            transientStorage.clearIterationPointers();
        }
        scratchRelease();
        ecPrivateKey.clearKey();
        ecKeyPair.getPrivate().clearKey();

        ISOException.throwIt(swCode);
    }

    /**
     * Dispatches a FIDO2 clientPin subcommand
     *
     * @param apdu    Request/response object
     * @param buffer  Buffer containing incoming request
     * @param readIdx Read index into bufferMem
     * @param lc      Length of incoming request, as sent by the platform
     */
    private void clientPINSubcommand(APDU apdu, byte[] buffer, short readIdx, short lc) {
        if (lc == 0) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        if ((buffer[readIdx] & 0xF0) != 0xA0) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }

        short numOptions = (short) (buffer[readIdx++] & 0x0F);
        if (numOptions < 2) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }
        if (readIdx >= (short)(lc - 4)) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }

        if (buffer[readIdx++] != 0x01) { // map key: pinProtocol
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }
        byte pinProtocol = buffer[readIdx++];
        checkPinProtocolSupported(apdu, pinProtocol);

        if (buffer[readIdx++] != 0x02) { // map key: subCommand
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        byte opType = buffer[readIdx++];

        switch (opType) {
            case FIDOConstants.CLIENT_PIN_GET_KEY_AGREEMENT:
                handleClientPinGetAgreement(apdu);
                return;
            case FIDOConstants.CLIENT_PIN_GET_RETRIES:
                handleClientPinGetRetries(apdu);
                return;
            case FIDOConstants.CLIENT_PIN_SET_PIN:
                handleClientPinInitialSet(apdu, buffer, readIdx, lc, pinProtocol);
                return;
            case FIDOConstants.CLIENT_PIN_CHANGE_PIN:
                handleClientPinChange(apdu, buffer, readIdx, lc, pinProtocol);
                return;
            case FIDOConstants.CLIENT_PIN_GET_PIN_TOKEN:
                handleClientPinGetToken(apdu, buffer, readIdx, lc, pinProtocol, false, numOptions);
                return;
            case FIDOConstants.CLIENT_PIN_GET_PIN_TOKEN_USING_PIN_WITH_PERMISSIONS:
                handleClientPinGetToken(apdu, buffer, readIdx, lc, pinProtocol, true, numOptions);
                return;
            default:
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_UNSUPPORTED_OPTION);
                break;
        }
    }

    /**
     * Processes the CTAP2 clientPin change subcommand
     *
     * @param apdu        Request/response object
     * @param buffer      Buffer containing incoming request
     * @param readIdx     Read index into request buffer
     * @param lc          Length of incoming request, as sent by the platform
     * @param pinProtocol Integer PIN protocol version in use
     */
    private void handleClientPinChange(APDU apdu, byte[] buffer, short readIdx, short lc, byte pinProtocol) {
        if (!pinSet) {
            // need to have a PIN to change a PIN...
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_NOT_SET);
        }

        // 32 bytes for PIN hash (for PIN protocol 2) and 16 for IV on padded PIN
        short scratchOff = scratchAlloc((short) (PIN_PAD_LENGTH + 48));

        readIdx = handlePinSetPreamble(apdu, buffer, readIdx, lc, scratch, scratchOff, true, pinProtocol);

        short wrappedPinLocation = readIdx;
        readIdx += PIN_PAD_LENGTH;
        if (pinProtocol == 2) {
            readIdx += 16; // IV for PIN pad
        }

        if (buffer[readIdx++] != 0x06) { // pinHashEnc
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }
        // Decrypt the sent PIN hash using the shared secret
        readIdx = sharedSecretDecrypt(apdu, buffer, readIdx, lc, (byte) 16,
                scratch, scratchOff, pinProtocol, true);

        // Use pinHash, now decrypted, to unlock the symmetric wrapping key (or fail if the PIN is wrong...)
        testAndReadyPIN(apdu, scratch, scratchOff, pinProtocol, (byte) 0x00);

        // Decrypt the real PIN
        sharedSecretDecrypt(apdu, buffer, wrappedPinLocation, lc, (byte) 64,
                scratch, scratchOff, pinProtocol, false);

        short realPinLength = 0;
        for (; realPinLength < PIN_PAD_LENGTH; realPinLength++) {
            if (scratch[(short)(scratchOff + realPinLength)] == 0x00) {
                break;
            }
        }
        if (realPinLength < 4 || realPinLength > 63) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_POLICY_VIOLATION);
        }

        rawSetPIN(apdu, scratch, scratchOff, realPinLength);

        byte[] outBuf = apdu.getBuffer();
        outBuf[0] = FIDOConstants.CTAP2_OK; // no data in the response to this command, just an OK status
        sendNoCopy(apdu, (short) 0, (short) 1);
    }

    /**
     * Checks incoming pinHash and returns a pinToken to the platform for use in future commands
     * (until the next reset, of course...)
     *
     * @param apdu              Request/response object
     * @param inBuffer          Buffer containing incoming request (will be overwritten)
     * @param readIdx           Read index into request buffer
     * @param lc                Length of incoming request, as sent by the platform
     * @param pinProtocol       Integer PIN protocol version in use
     * @param expectPermissions If true, require permissions to be requested. If false, disallow them and use defaults
     * @param numOptions        Number of CBOR options present
     */
    private void handleClientPinGetToken(APDU apdu, byte[] inBuffer,
                                         short readIdx, short lc, byte pinProtocol, boolean expectPermissions,
                                         short numOptions) {
        if (!pinSet) {
            // duh
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_NOT_SET);
        }

        if (transientStorage.getPinTriesSinceReset() == PIN_TRIES_PER_RESET) {
            // Proceed no further: PIN is blocked until authenticator is powered off and on again
            // NO TOKEN FOR YOU. NEXT!
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_BLOCKED);
        }

        if (inBuffer[readIdx++] != 0x03) { // map key: keyAgreement
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        // allowing the consumeKeyAgreement call to use the start of the APDU buffer will result in it overwriting
        // some data we need. So let's save and then restore that here, into privateScratch.
        byte[] apduBuffer = apdu.getBuffer();
        short savePoint = (short)(readIdx + KEY_POINT_LENGTH * 2);
        Util.arrayCopyNonAtomic(apduBuffer, savePoint,
                privateScratch, (short) 32, (short) 32);

        readIdx = consumeKeyAgreement(apdu, inBuffer, readIdx, pinProtocol, true);

        // Now restore the saved data
        Util.arrayCopyNonAtomic(privateScratch, (short) 32,
                apduBuffer, savePoint, (short) 32);

        if (inBuffer[readIdx++] != 0x06) { // map key: pinHashEnc
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        // Decrypt the 16 bytes of PIN verification first (in place)
        readIdx = sharedSecretDecrypt(apdu, inBuffer, readIdx, lc, (byte) 16,
                inBuffer, (short) 0, pinProtocol, true);

        // parse permissions before readying the PIN, so that we can set the right perms on it
        byte permissions = (byte)(FIDOConstants.PERM_MAKE_CREDENTIAL | FIDOConstants.PERM_GET_ASSERTION);
        short permRpIdIdx = -1;
        short permRpIdLen = -1;

        if (expectPermissions) {
            if (readIdx == lc || numOptions < 5) {
                // We expect permissions, but we don't have any!
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
            }
            if (inBuffer[readIdx++] != 0x09) { // map key: permissions
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
            }
            if (inBuffer[readIdx] == 0x18) { // one-byte integer follows
                permissions = inBuffer[++readIdx];
                readIdx++;
            } else if (inBuffer[readIdx] < 0x18) { // in-place integer
                permissions = inBuffer[readIdx++];
            }

            if (numOptions == 6) {
                if (inBuffer[readIdx++] == 0x0A) { // map key: rpId
                    if (inBuffer[readIdx] >= 0x60 && inBuffer[readIdx] <= 0x77) {
                        // string with embedded length
                        permRpIdLen = (short)(inBuffer[readIdx++] - 0x60);
                    } else if (inBuffer[readIdx] == 0x78) {
                        // string with one-byte length
                        permRpIdLen = ub(inBuffer[++readIdx]);
                        readIdx++;
                    }
                    if (permRpIdLen <= 0) {
                        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                    }

                    permRpIdIdx = readIdx;
                    readIdx += permRpIdLen;
                }
                // else ignore: could be options we don't support / know about
            }
        } else {
            if (numOptions > 4) {
                // We have more to read, but we don't expect anything more
                sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_PARAMETER);
            }
        }

        if (permissions == 0) {
            // FIDO spec disallows providing empty permissions bitfield
            sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_PARAMETER);
        }

        if (expectPermissions && permRpIdIdx == -1) {
            // We can't allow a makeCredential or getAssertion without an RP ID binding
            if ((permissions & FIDOConstants.PERM_MAKE_CREDENTIAL) != 0
                || (permissions & FIDOConstants.PERM_GET_ASSERTION) != 0) {
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
            }
        }

        testAndReadyPIN(apdu, inBuffer, (short) 0, pinProtocol, permissions);
        if (permRpIdIdx != -1) {
            permissionsRpId[0] = 0x01; // RP ID restriction enabled
            sha256.doFinal(inBuffer, permRpIdIdx, permRpIdLen,
                    permissionsRpId, (short) 1);
        } else if (permissionsRpId[0] != 0x00) {
            // no permissions RP ID provided - disable
            permissionsRpId[0] = 0x00;
        }

        // Output below here

        short writeOffset = 0;
        byte[] buffer = apdu.getBuffer();

        buffer[writeOffset++] = FIDOConstants.CTAP2_OK;
        buffer[writeOffset++] = (byte) 0xA1; // map: one item
        buffer[writeOffset++] = 0x02; // map key: pinToken
        writeOffset = sharedSecretEncrypt(pinToken, (short) 0, (short) pinToken.length, buffer, writeOffset,
                pinProtocol, true);

        sendNoCopy(apdu, (short) 0, writeOffset);
    }

    /**
     * Encrypt data destined for the platform using a shared secret, and pack the result into a target buffer
     *
     * @param inBuf Buffer containing data to be encrypted
     * @param inOffset Read offset into input buffer
     * @param length Length of data to be encrypted
     * @param outBuf Output buffer into which to write byte string with optional CBOR header
     * @param writeOffset Write offset into output buffer
     * @param pinProtocol Integer PIN protocol number
     * @param addCBORHeader If true, add CBOR byte array header to output as well
     *
     * @return New write index into output buffer after adding encrypted data
     */
    private short sharedSecretEncrypt(byte[] inBuf, short inOffset, short length, byte[] outBuf, short writeOffset,
                                      byte pinProtocol, boolean addCBORHeader) {
        short rawLength = length;
        if (pinProtocol == 2) {
            // byte array length includes 16-byte IV
            rawLength += 16;
        }

        if (addCBORHeader) {
            writeOffset = encodeIntLenTo(outBuf, writeOffset, rawLength, true);
        }

        if (pinProtocol == 2) {
            // Write out a random 16-byte IV and set up the wrapper to use it
            random.generateData(outBuf, writeOffset, (short) 16);
            sharedSecretWrapper.init(sharedSecretAESKey, Cipher.MODE_ENCRYPT, outBuf, writeOffset, (short) 16);
            writeOffset += 16;
        }

        writeOffset += sharedSecretWrapper.doFinal(inBuf, inOffset, length,
                outBuf, writeOffset);

        return writeOffset;
    }

    /**
     * Unwrap platform-encrypted data from a CBOR buffer into a designated output buffer
     *
     * @param apdu Request/response object
     * @param inBuf Buffer from which to read a CBOR byte array representing platform-encrypted data
     * @param readIdx Read index into input buffer
     * @param lc Length of incoming request, as sent by the platform
     * @param expectedLength How long the decrypted result should be
     * @param outputBuffer Buffer into which to write the decrypted result
     * @param outOff Offset into output buffer
     * @param pinProtocol Integer PIN protocol number
     * @param consumeCBOR If true, consume a CBOR byte array header before the data
     *
     * @return New read index into input buffer after consuming encrypted data
     */
    private short sharedSecretDecrypt(APDU apdu, byte[] inBuf, short readIdx, short lc, byte expectedLength,
                                      byte[] outputBuffer, short outOff, byte pinProtocol, boolean consumeCBOR) {
        byte rawLength = expectedLength;
        if (pinProtocol == 2) {
            // PIN protocol two means 16 bytes of IV before actual data, sooo...
            rawLength += 16;
        }

        if (consumeCBOR) {
            if (rawLength < 24) {
                // Single byte for type and length
                if (inBuf[readIdx++] != (byte)(0x40 + rawLength)) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
            } else {
                if (inBuf[readIdx++] != 0x58) { // byte string, one-byte length
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
                if (inBuf[readIdx++] != rawLength) {
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
            }
        }

        if (readIdx > (short)(lc - rawLength)) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_INVALID_CBOR);
        }

        if (pinProtocol == 2) {
            // Set IV and advance past it
            sharedSecretUnwrapper.init(sharedSecretAESKey, Cipher.MODE_DECRYPT, inBuf, readIdx, (short) 16);
            readIdx += 16;
        }

        short unwrapped = sharedSecretUnwrapper.doFinal(inBuf, readIdx, expectedLength,
                outputBuffer, outOff);
        if (unwrapped != expectedLength) {
            throwException(ISO7816.SW_UNKNOWN);
        }

        return (short)(readIdx + expectedLength);
    }

    /**
     * Checks the SHA-256 hash of a PIN for correctness, and if it is correct, readies the authenticator unwrapping
     * key for use. After a successful call, symmetricWrapper and symmetricUnwrapper may be used.
     *
     * @param apdu Request/response object
     * @param buf Buffer potentially containing the first 16 bytes of the SHA-256 hash of the PIN
     * @param off Offset of the putative PIN hash within the given buffer
     * @param pinProtocol PIN protocol used to pass PIN in
     * @param pinPermissions Bitfield representing permissions to be associated with the PIN if correct
     */
    private void testAndReadyPIN(APDU apdu, byte[] buf, short off, byte pinProtocol, byte pinPermissions) {
        short pinRetryIndex = pinRetryCounter.prepareIndex();
        if (pinRetryCounter.getRetryCount(pinRetryIndex) <= 0) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_BLOCKED);
        }

        if (transientStorage.getPinTriesSinceReset() >= PIN_TRIES_PER_RESET) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_BLOCKED);
        }

        // Use PBKDF on the hash to derive a potential PIN key
        PBKDF2(apdu, buf, off);

        // Use wrapping key because it's always transient, and it's garbage right now anyhow
        wrappingKey.setKey(privateKeySpace, (short) 0);
        pinUnwrapper.init(wrappingKey, Cipher.MODE_DECRYPT, wrappingIV, (short) 0, (short) wrappingIV.length);

        pinUnwrapper.doFinal(wrappingKeySpace, (short) 0, (short) wrappingKeySpace.length,
                privateKeySpace, (short) 0);

        short scratchOff = scratchAlloc((short) 32);

        // Compute HMAC-SHA256 of first 32 bytes of wrappingKeyValidation
        hmacSha256(apdu, privateKeySpace, (short) 0,
                   wrappingKeyValidation, (short) 0, (short) 32,
                   scratch, scratchOff, false);

        // decrement retry counter *before* checking if it's correct:
        // this will be reset to max if it is correct, and otherwise it's theoretically possible to
        // remove power from the authenticator between it determining correctness and decrementing the
        // counter. So we'll accept the risk that a good PIN still results in the counter going down
        // in the event of a strange failure.
        transientStorage.incrementPinTriesSinceReset();
        pinRetryCounter.decrement(pinRetryIndex);

        // ... and check that the result equals the second 32 bytes. If it does, we have the correct key.
        if (Util.arrayCompare(wrappingKeyValidation, (short) 32,
                scratch, scratchOff, (short) 32) == 0) {
            // Good PIN!
            pinRetryCounter.reset(pinRetryIndex);
            transientStorage.setPinProtocolInUse(pinProtocol, pinPermissions);
            wrappingKey.setKey(privateKeySpace, (short) 0);
            initSymmetricCrypto(); // Need to re-key the wrapper since we messed it up above
            transientStorage.clearPinTriesSinceReset();
            scratchRelease((short) 32);
            return;
        }

        // BAD PIN
        wrappingKey.clearKey();
        forceInitKeyAgreementKey();
        if (transientStorage.getPinTriesSinceReset() == PIN_TRIES_PER_RESET) {
            // You've gone and done it now. Need to power the authenticator off and on again to try again.
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_AUTH_BLOCKED);
        }
        sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_INVALID);
    }

    /**
     * Performs heavy lifting for setting new PINs and changing existing ones. After a successful call,
     * the PIN is not necessarily correct, but the platform-authenticator shared secret is consistent with it.
     *
     * @param apdu Request/response object
     * @param buffer Buffer containing incoming request
     * @param readIdx Starting index in request/response buffer of the keyAgreement (3rd) CTAP2 CBOR argument
     * @param lc Length of the incoming request, as declared by the platform
     * @param outBuf Buffer for result and scratch: minimum PIN_PAD_LENGTH+16 bytes allocated
     * @param outOffset Offset into output buffer. Again, must be at least PIN_PAD_LENGTH+16 bytes from the end of the buffer
     * @param expectPinHashEnc If true, expect that pinAuth matches hash(newPinEnc || pinHashEnc). In other words,
     *                         that an existing PIN was provided and this is a change-PIN operation
     * @param pinProtocol Integer PIN protocol version in use
     *
     * @return Index into request/response buffer of the encrypted new PIN
     */
    private short handlePinSetPreamble(APDU apdu, byte[] buffer, short readIdx, short lc, byte[] outBuf, short outOffset,
                                       boolean expectPinHashEnc, byte pinProtocol) {
        if (buffer[readIdx++] != 0x03) { // map key: keyAgreement
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        readIdx = consumeKeyAgreement(apdu, buffer, readIdx, pinProtocol, false);

        if (buffer[readIdx++] != 0x04) { // map key: pinAuth
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        short pinAuthLength = 16;
        if (pinProtocol == 1) {
            if (buffer[readIdx++] != 0x50) { // byte string: 16 bytes long
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
        } else if (pinProtocol == 2) {
            if (buffer[readIdx++] != 0x58) { // byte string with one-byte length
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
            if (buffer[readIdx++] != 0x20) { // 32 bytes long
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
            }
            pinAuthLength = 32;
        } else {
            sendErrorByte(apdu, FIDOConstants.CTAP1_ERR_INVALID_PARAMETER);
        }
        short pinAuthIdx = readIdx;
        readIdx += pinAuthLength;

        if (buffer[readIdx++] != 0x05) { // map key: newPinEnc
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }

        short bLen = 0;
        byte pstrType = buffer[readIdx++];
        if (pstrType == 0x58) { // byte string, one-byte length
            bLen = ub(buffer[readIdx++]);
        } else {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }

        short expectedLength = PIN_PAD_LENGTH;
        if (pinProtocol == 2) {
            expectedLength += 16; // 16-byte IV
        }

        if (bLen != expectedLength) { // standard-mandated minimum pad for PINs
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_INVALID);
        }

        // Verify pinAuth before we proceed
        if (expectPinHashEnc) {
            // Need to buffer-pack newPinEnc and pinHashEnc together before verifying
            short readAheadIdx = readIdx;

            Util.arrayCopyNonAtomic(buffer, readAheadIdx,
                    outBuf, outOffset, bLen);

            readAheadIdx += bLen;

            if (buffer[readAheadIdx++] != 0x06) { // pinHashEnc
                sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_REQUIRED);
            }
            if (pinProtocol == 1) {
                if (buffer[readAheadIdx++] != 0x50) { // byte array, 16 bytes long
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
                Util.arrayCopyNonAtomic(buffer, readAheadIdx,
                        outBuf, (short)(outOffset + bLen), (short) 16);

                hmacSha256(apdu, sharedSecretVerifyKey, (short) 0,
                        outBuf, outOffset, (short)(bLen + 16),
                        outBuf, outOffset, false);
            } else {
                if (buffer[readAheadIdx++] != 0x58) { // byte string, one-byte length
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
                if (buffer[readAheadIdx++] != 0x20) { // 32: sixteen bytes of IV and 16 bytes of hash
                    sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
                }
                Util.arrayCopyNonAtomic(buffer, readAheadIdx,
                        outBuf, (short)(outOffset + bLen), (short) 32);

                hmacSha256(apdu, sharedSecretVerifyKey, (short) 0,
                        outBuf, outOffset, (short)(bLen + 32),
                        outBuf, outOffset, false);
            }
        } else {
            hmacSha256(apdu, sharedSecretVerifyKey, (short) 0,
                    buffer, readIdx, bLen,
                    outBuf, outOffset, false);
        }

        if (Util.arrayCompare(outBuf, outOffset,
                buffer, pinAuthIdx, pinAuthLength) != 0) {
            // Messed up crypto or invalid input: cannot proceed
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_INVALID);
        }

        // Verification OK

        return readIdx;
    }

    /**
     * Handles the FIDO2 clientPin initial set subcommand
     *
     * @param apdu        Request/response object
     * @param buffer      Buffer containing incoming request
     * @param readIdx     Read index into request buffer
     * @param lc          Length of incoming request, as sent by the platform
     * @param pinProtocol Integer PIN protocol version in use
     */
    private void handleClientPinInitialSet(APDU apdu, byte[] buffer, short readIdx, short lc, byte pinProtocol) {
        if (pinSet) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_REQUIRED);
        }

        short scratchOff = scratchAlloc((short) 80);

        readIdx = handlePinSetPreamble(apdu, buffer, readIdx, lc, scratch, scratchOff, false, pinProtocol);

        readIdx = sharedSecretDecrypt(apdu, buffer, readIdx, lc, (byte) 64,
                scratch, scratchOff, pinProtocol, false);

        short realPinLength = 0;
        for (; realPinLength < 64; realPinLength++) {
            if (scratch[(short)(scratchOff + realPinLength)] == 0x00) {
                break;
            }
        }
        if (realPinLength < 4 || realPinLength > 63) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_POLICY_VIOLATION);
        }

        rawSetPIN(apdu, scratch, scratchOff, realPinLength);

        byte[] outBuf = apdu.getBuffer();
        outBuf[0] = FIDOConstants.CTAP2_OK;
        sendNoCopy(apdu, (short) 0, (short) 1);
    }

    /**
     * Consumes a CBOR object representing the platform's public key from bufferMem.
     *
     * After successful call, DH platform<->authenticator shared secret is available:
     * sharedSecretWrapper and sharedSecretUnwrapper may be used
     *
     * @param apdu Request/response object
     * @param buffer Buffer containing incoming request
     * @param readIdx Index of the platform public key in bufferMem
     * @param pinProtocol Integer PIN protocol version in use
     * @param clobberBuffers If true, uses both APDU buffer (96 bytes) and privateScratch (32 bytes) as temp space
     *
     * @return New read index position in incoming buffer after consuming the key agreement CBOR block
     */
    private short consumeKeyAgreement(APDU apdu, byte[] buffer, short readIdx, byte pinProtocol,
                                      boolean clobberBuffers) {
        if (buffer[readIdx++] != (byte) 0xA5) { // map, with five entries
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_MISSING_PARAMETER);
        }
        if (Util.arrayCompare(buffer, readIdx,
                CannedCBOR.PUBLIC_KEY_DH_ALG_PREAMBLE, (short) 0, (short) CannedCBOR.PUBLIC_KEY_DH_ALG_PREAMBLE.length) != 0) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_UNSUPPORTED_ALGORITHM);
        }
        readIdx += CannedCBOR.PUBLIC_KEY_DH_ALG_PREAMBLE.length;

        short xIdx = readIdx;
        readIdx += KEY_POINT_LENGTH;
        if (buffer[readIdx++] != 0x22) { // map key: y-point
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }
        if (buffer[readIdx++] != 0x58) { // byte string, one-byte length
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }
        if (buffer[readIdx++] != KEY_POINT_LENGTH) {
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_CBOR_UNEXPECTED_TYPE);
        }

        short yIdx = readIdx;
        readIdx += KEY_POINT_LENGTH;

        prepareSharedSecret(apdu, buffer, xIdx, yIdx, pinProtocol, clobberBuffers);
        return readIdx;
    }

    /**
     * Perform the underlying mechanics of setting or changing a PIN. After call,
     * a PIN is set, but the authenticator is in the no-PIN-provided state, and
     * symmetric crypto is unavailable
     *
     * @param apdu Request/response object
     * @param pinBuf Buffer containing raw (unhashed) new PIN
     * @param offset Offset into request buffer of start of PIN
     * @param pinLength Length in bytes of new PIN
     */
    private void rawSetPIN(APDU apdu, byte[] pinBuf, short offset, short pinLength) {
        if (pinSet && transientStorage.getPinProtocolInUse() == 0) {
            // We already have a PIN, but we haven't unlocked with it this boot...
            // that's not going to work.
            sendErrorByte(apdu, FIDOConstants.CTAP2_ERR_PIN_REQUIRED);
        }

        // Take the SHA256 of the PIN before we start, and only pass the first 16 bytes to the change function
        // since that's all the protocol sends us to validate the PIN, that is our *real* PIN...
        sha256.doFinal(pinBuf, offset, pinLength,
                pinBuf, offset);

        // Set pinWrapper to use a key we *derived* from the PIN
        // If the PIN is weak, this will modestly increase the difficulty of brute forcing the wrapping key
        PBKDF2(apdu, pinBuf, offset);
        pinWrapKey.setKey(privateKeySpace, (short) 0);
        pinWrapper.init(pinWrapKey, Cipher.MODE_ENCRYPT, wrappingIV, (short) 0, (short) wrappingIV.length);

        // re-encrypt the current wrapping key using the PIN
        // and ATOMICALLY replace the old value with the new one at the same time as we change the PIN itself
        JCSystem.beginTransaction();
        boolean ok = false;
        try {
            if (pinSet) {
                wrappingKey.getKey(wrappingKeySpace, (short) 0);
            }

            pinSet = true;
            short pinIdx = pinRetryCounter.prepareIndex();
            pinRetryCounter.reset(pinIdx);

            // Encrypt the wrapping key with the PIN key
            pinWrapper.doFinal(wrappingKeySpace, (short) 0, (short) wrappingKeySpace.length,
                    wrappingKeySpace, (short) 0);

            wrappingKey.clearKey();
            pinWrapKey.clearKey();

            forceInitKeyAgreementKey();

            ok = true;
        } finally {
            if (ok) {
                JCSystem.commitTransaction();
            } else {
                JCSystem.abortTransaction();
            }
        }
    }

    /**
     * Implementation of a weakish Password-Based Key Derivation, Version 2 (PBKDF2), as described in RFC2898.
     * After call, privateKeySpace contains a 32-byte-long key deterministically derived from the incoming PIN.
     *
     * Note that this function clobbers privateScratch!
     *
     * @param apdu Request/response object
     * @param pinBuf Buffer containing the (hashed) PIN, 16 bytes long
     * @param offset Offset of PIN in incoming buffer
     */
    private void PBKDF2(APDU apdu, byte[] pinBuf, short offset) {
        Util.arrayCopyNonAtomic(pinKDFSalt, (short) 0,
                privateScratch, (short) 0, (short) pinKDFSalt.length);
        // PBKDF2 has us concatenate the first iteration number (1) as a 32-bit int onto the end of the salt for iter1
        privateScratch[(short)pinKDFSalt.length] = 0x00;
        privateScratch[(short)(pinKDFSalt.length + 1)] = 0x00;
        privateScratch[(short)(pinKDFSalt.length + 2)] = 0x00;
        privateScratch[(short)(pinKDFSalt.length + 3)] = 0x01;
        for (short i = (short)(pinKDFSalt.length + 4); i < 32; i++) {
            privateScratch[i] = 0x00;
        }

        // Copy the 16 bytes of key and 16 zeroes into the scratch buffer to use as private key
        Util.arrayCopyNonAtomic(pinBuf, offset,
                privateScratch, (short) 32, (short) 16);
        Util.arrayFillNonAtomic(privateScratch, (short) 48, (short) 16, (byte) 0x00);

        for (short i = 0; i < PIN_KDF_ITERATIONS; i++) {
            // Hash the current iteration value with the password-as-private-key HMAC
            hmacSha256(apdu, privateScratch, (short) 32,
                    privateScratch, (short) 0, (short) 32,
                    privateScratch, (short) 0, false);
            if (i == 0) {
                Util.arrayCopyNonAtomic(privateScratch, (short) 0,
                        privateKeySpace, (short) 0, (short) 32);
            } else {
                // XOR the previous result with the new one
                for (short j = 0; j < 32; j++) {
                    privateKeySpace[j] = (byte)(privateKeySpace[j] ^ privateScratch[j]);
                }
            }
        }
    }

    /**
     * Handle a clientPINGetAgreement CTAP2 request
     *
     * @param apdu The request/response object
     */
    private void handleClientPinGetAgreement(APDU apdu) {
        // Send public key of authenticatorKeyAgreementKey back

        byte[] outBuf = apdu.getBuffer();

        short outputLen = 0;
        outBuf[outputLen++] = FIDOConstants.CTAP2_OK;
        outBuf[outputLen++] = (byte) 0xA1; // map - one entry
        outBuf[outputLen++] = 0x01; // map key: keyAgreement
        outBuf[outputLen++] = (byte) 0xA5; // map: five entries
        outputLen = Util.arrayCopyNonAtomic(CannedCBOR.PUBLIC_KEY_DH_ALG_PREAMBLE, (short) 0,
                outBuf, outputLen, (short) CannedCBOR.PUBLIC_KEY_DH_ALG_PREAMBLE.length);


        // Place public key into UPPER half of APDU buffer, away from where we are building the response

        ((ECPublicKey) authenticatorKeyAgreementKey.getPublic()).getW(outBuf, (short) 128);
        outputLen = writePubKey(outBuf, outputLen, outBuf, (short) 129); // note: +1 to skip keyEncodingType byte

        sendNoCopy(apdu, (short) 0, outputLen);
    }

    /**
     * Handle a clientPINGetRetries CTAP2 request
     *
     * @param apdu The request/response object
     */
    private void handleClientPinGetRetries(APDU apdu) {
        short pinIdx = pinRetryCounter.prepareIndex();

        byte[] outBuf = apdu.getBuffer();
        short outputLen = 0;
        outBuf[outputLen++] = FIDOConstants.CTAP2_OK;
        outBuf[outputLen++] = (byte) 0xA2; // map - two entries
        outBuf[outputLen++] = 0x03; // map key: retries
        outBuf[outputLen++] = pinRetryCounter.getRetryCount(pinIdx);
        outBuf[outputLen++] = 0x04; // map key: powerCycleState
        outBuf[outputLen++] = (byte) (transientStorage.getPinTriesSinceReset() >= PIN_TRIES_PER_RESET
                ? 0xF5 : 0xF4); // true or false

        sendNoCopy(apdu, (short) 0, outputLen);
    }

    /**
     * Forcibly refresh per-boot data. This includes the PIN token and the ephemeral EC pair used for
     * DH between the platform and the authenticator.
     */
    private void forceInitKeyAgreementKey() {
        P256Constants.setCurve((ECKey) authenticatorKeyAgreementKey.getPrivate());
        P256Constants.setCurve((ECKey) authenticatorKeyAgreementKey.getPublic());
        authenticatorKeyAgreementKey.genKeyPair();
        keyAgreement.init(authenticatorKeyAgreementKey.getPrivate());

        transientStorage.setPinProtocolInUse((byte) 0, (byte) 0);
        random.generateData(pinToken, (short) 0, (short) pinToken.length);

        transientStorage.setPlatformKeySet();
    }

    /**
     * If the authenticator-to-and-from-platform key agreement hasn't already been set up this boot, set it up
     */
    private void initKeyAgreementKeyIfNecessary() {
        if (!transientStorage.isPlatformKeySet()) {
            forceInitKeyAgreementKey();
        }
    }

    /**
     * Gets AES encipherment
     *
     * @return A Cipher set up for AES with an authenticator-supported block size
     */
    private Cipher getAES() {
        // NB: a 128-bit block size is used even for AES256. Just because this says "128"
        // doesn't say anything about the key length
        return Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
    }

    /**
     * Platform method called when the applet is deselected. Clears request/response chaining state, etc
     */
    public void deselect() {
        // This one NEEDS to be cleared, because we *might* be using a TRANSIENT_DESELECT private key
        // for the platformKeyAgreementKey. If we are, it'll disappear into the ether after deselect,
        // so we need to make sure that we re-initialize it when we're selected again.
        transientStorage.clearOnDeselect();

        // Other stuff
        permissionsRpId[0] = 0x00;
    }

    /**
     * Gets an EC sig object
     *
     * @return An elliptic curve signature object suitable for the FIDO2 standard - ECDSA-SHA256
     */
    private Signature getECSig() {
         return Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
    }

    /**
     * Gets an elliptic curve private key object.
     *
     * @return An uninitialized EC private key, ideally in RAM, but in flash if the authenticator doesn't support in-memory
     */
    private ECPrivateKey getECPrivKey(boolean forceAllowTransient) {
        if (!forceAllowTransient && !MINIMIZE_RAM_USE) {
            try {
                return (ECPrivateKey) KeyBuilder.buildKey(KeyBuilder.TYPE_EC_FP_PRIVATE_TRANSIENT_DESELECT, KeyBuilder.LENGTH_EC_FP_256, false);
            } catch (CryptoException e) {
                // Oh well, unsupported, use normal RAM or flash instead
            }

            try {
                return (ECPrivateKey) KeyBuilder.buildKey(KeyBuilder.TYPE_EC_FP_PRIVATE_TRANSIENT_RESET, KeyBuilder.LENGTH_EC_FP_256, false);
            } catch (CryptoException e) {
                // Oh well, unsupported, use flash instead
            }
        }

        return (ECPrivateKey) KeyBuilder.buildKey(KeyBuilder.TYPE_EC_FP_PRIVATE, KeyBuilder.LENGTH_EC_FP_256, false);
    }

    /**
     * Get a persistent AES key
     *
     * @return An AESKey object that will retain its contents indefinitely
     */
    private AESKey getPersistentAESKey() {
        return (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_256, false);
    }

    /**
     * Get a transient AES key
     *
     * @return An AESKey object that will go away at power reset
     */
    private AESKey getTransientAESKey() {
        return (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES_TRANSIENT_DESELECT, KeyBuilder.LENGTH_AES_256, false);
    }

    /**
     * When the wrappingKey is correct set up, prepare symmetricWrapper and symmetricUnwrapper.
     * After calling this function, blobs can be wrapped so only we can unwrap them again,
     * or unwrapped after being so wrapped.
     */
    private void initSymmetricCrypto() {
        symmetricWrapper.init(wrappingKey, Cipher.MODE_ENCRYPT, wrappingIV, (short) 0, (short) wrappingIV.length);
        symmetricUnwrapper.init(wrappingKey, Cipher.MODE_DECRYPT, wrappingIV, (short) 0, (short) wrappingIV.length);
    }

    /**
     * Entry point for installing the applet!
     * @param array install parameters array
     * @param offset install parameters offset
     * @param length install parameters length
     * @throws ISOException If anything goes wrong during installation
     */
    public static void install(byte[] array, short offset, byte length)
            throws ISOException {
        FIDO2Applet applet = new FIDO2Applet(array, offset, length);

        // Javacard API requires this call to know we succeeded and set the app up with the platform
        applet.register();
    }

    /**
     * Setup method preparing all the internal state
     *
     * @param array install parameters array
     * @param offset install parameters offset
     * @param length install parameters length
     */
    protected FIDO2Applet(byte[] array, short offset, byte length) {
        try {
            JCSystem.requestObjectDeletion();
        } catch (Exception e) {
        }

        // Flash usage
        pinKDFSalt = new byte[28];
        wrappingKeySpace = new byte[32];
        wrappingKeyValidation = new byte[64];
        hmacWrapperBytes = new byte[32];
        wrappingIV = new byte[16];
        wrappingKey = getTransientAESKey(); // Our most important treasure, from which all other crypto is born...
        // Resident key data, of course, must all be in flash. Losing that on reset would be Bad
        residentKeyData = new byte[NUM_RESIDENT_KEY_SLOTS * CREDENTIAL_ID_LEN];
        residentKeyValidity = new boolean[NUM_RESIDENT_KEY_SLOTS];
        for (short i = 0; i < NUM_RESIDENT_KEY_SLOTS; i++) {
            // better safe than sorry
            residentKeyValidity[i] = false;
        }
        residentKeyUserIds = new byte[NUM_RESIDENT_KEY_SLOTS * MAX_USER_ID_LENGTH];
        residentKeyUserIdLengths = new byte[NUM_RESIDENT_KEY_SLOTS];
        residentKeyRPIds = new byte[NUM_RESIDENT_KEY_SLOTS * MAX_RESIDENT_RP_ID_LENGTH];
        residentKeyRPIdLengths = new byte[NUM_RESIDENT_KEY_SLOTS];
        residentKeyPublicKeys = new byte[NUM_RESIDENT_KEY_SLOTS * KEY_POINT_LENGTH * 2];
        residentKeyUniqueRP = new boolean[NUM_RESIDENT_KEY_SLOTS];
        numResidentCredentials = 0;
        numResidentRPs = 0;
        resetRequested = false;
        counter = new SigOpCounter();
        pinRetryCounter = new PinRetryCounter((byte) MAX_PIN_RETRIES);

        // Trivial amounts of flash, object allocations without buffers
        random = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
        keyAgreement = KeyAgreement.getInstance(KeyAgreement.ALG_EC_SVDP_DH_PLAIN, false);
        pinUnwrapper = getAES();
        pinWrapper = getAES();
        symmetricWrapper = getAES();
        symmetricUnwrapper = getAES();
        sharedSecretWrapper = getAES();
        sharedSecretUnwrapper = getAES();
        attester = getECSig();
        sha256 = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);

        initTransientStorage();
    }

    private byte[] getTempOrFlashByteBuffer(short len) {
        if (MINIMIZE_RAM_USE) {
            // Yuck.
            return new byte[len];
        }
        return JCSystem.makeTransientByteArray(len, JCSystem.CLEAR_ON_DESELECT);
    }

    private void initTransientStorage() {
        // RAM usage - direct buffers
        bufferMem = getTempOrFlashByteBuffer(BUFFER_MEM_SIZE);
        scratch = getTempOrFlashByteBuffer(SCRATCH_SIZE);
        privateScratch = JCSystem.makeTransientByteArray(PRIVATE_SCRATCH_SIZE, JCSystem.CLEAR_ON_DESELECT);
        privateKeySpace = JCSystem.makeTransientByteArray((short) 32, JCSystem.CLEAR_ON_DESELECT);
        pinToken = JCSystem.makeTransientByteArray((short) 32, JCSystem.CLEAR_ON_DESELECT);
        permissionsRpId = getTempOrFlashByteBuffer((short)(RP_HASH_LEN + 1));

        // RAM usage - (ideally) ephemeral keys
        authenticatorKeyAgreementKey = new KeyPair(
                (ECPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_EC_FP_PUBLIC, KeyBuilder.LENGTH_EC_FP_256, false),
                getECPrivKey(false)
        );
        ecKeyPair = new KeyPair(
                (ECPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_EC_FP_PUBLIC, KeyBuilder.LENGTH_EC_FP_256, false),
                getECPrivKey(false)
        );
        ecPrivateKey = getECPrivKey(false);
        if (MINIMIZE_RAM_USE) {
            pinWrapKey = getPersistentAESKey();
            sharedSecretAESKey = getPersistentAESKey();
        } else {
            pinWrapKey = getTransientAESKey();
            sharedSecretAESKey = getTransientAESKey();
        }
        sharedSecretVerifyKey = getTempOrFlashByteBuffer((short) 32);

        transientStorage = new TransientStorage();

        // Actual init work
        // Four things are truly random and persist until we hard-FIDO2-reset the authenticator:
        // - the salt we use for deriving keys from PINs
        random.generateData(pinKDFSalt, (short) 0, (short) pinKDFSalt.length);
        // - the IV we use for encrypting and decrypting blobs sent by the authenticator TO the authenticator
        random.generateData(wrappingIV, (short) 0, (short) wrappingIV.length);
        // - the key we use for converting a credential private key into an hmac-secret ... uh ... secret
        random.generateData(hmacWrapperBytes, (short) 0, (short) hmacWrapperBytes.length);
        initKeyAgreementKeyIfNecessary();

        // ... aaand finally, the actual wrapping key
        resetWrappingKey();

        // Per-credential keypairs are on the P256 curve
        P256Constants.setCurve((ECKey) ecKeyPair.getPrivate());
        P256Constants.setCurve((ECKey) ecKeyPair.getPublic());
    }

}
