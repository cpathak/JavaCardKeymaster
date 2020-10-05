package com.android.javacard.keymaster;

import javacard.framework.JCSystem;
import javacard.framework.Util;
import javacard.security.CryptoException;
import javacard.security.Signature;
import javacardx.crypto.AEADCipher;
import javacardx.crypto.Cipher;

public class KMOperationImpl implements KMOperation {
  private Cipher cipher;
  private Signature signature;
  private short cipherAlg;
  private short padding;
  private short mode;
  private boolean verificationFlag;
  private short blockMode;
  private short macLength;
  private short aesGcmUpdatedLen;

  public KMOperationImpl() {   
  }

  public short getMode() {
    return mode;
  }

  public void setMode(short mode) {
    this.mode = mode;
  }

  public short getMacLength() {
    return macLength;
  }

  public void setMacLength(short macLength) {
    this.macLength = macLength;
  }

  public short getPaddingAlgorithm() {
    return padding;
  }

  public void setPaddingAlgorithm(short alg) {
    padding = alg;
  }

  public void setBlockMode(short mode){
    blockMode =  mode;
  }

  public short getBlockMode(){
    return blockMode;
  }
  
  public short getCipherAlgorithm() {
    return cipherAlg;
  }

  public void setCipherAlgorithm(short cipherAlg) {
    this.cipherAlg = cipherAlg;
  }


  public void setCipher(Cipher cipher) {
    this.cipher = cipher;
  }

  public void setSignature(Signature signer) {
    this.signature = signer;
  }

  private void resetCipher() {
    JCSystem.beginTransaction();
    cipher = null;
    macLength = 0;
    aesGcmUpdatedLen = 0;
    blockMode = 0;
    mode = 0;
    cipherAlg = 0;
    JCSystem.commitTransaction();
  }

  //@Override
  public short update(byte[] inputDataBuf, short inputDataStart, short inputDataLength,
      byte[] outputDataBuf, short outputDataStart) {
    short len = cipher.update(inputDataBuf,inputDataStart,inputDataLength,outputDataBuf,outputDataStart);
    if(cipherAlg == KMType.AES && blockMode == KMType.GCM) {
      //Every time Block size data is stored as intermediate result.
      aesGcmUpdatedLen += (short)(inputDataLength - len);
    }
    return len;
  }

  //@Override
  public short update(byte[] inputDataBuf, short inputDataStart, short inputDataLength) {
    signature.update(inputDataBuf,inputDataStart,inputDataLength);
    return 0;
  }

  //@Override
  public short finish(byte[] inputDataBuf, short inputDataStart, 
      short inputDataLen, byte[] outputDataBuf, short outputDataStart) {
    byte[] tmpArray = KMJCOPSimProvider.getInstance().tmpArray;
    //byte[] tmpArray = JCSystem.makeTransientByteArray((short)256, JCSystem.CLEAR_ON_DESELECT);
    short len = 0;
    try {
      if(cipherAlg == KMType.AES && blockMode == KMType.GCM) {
        if(mode == KMType.DECRYPT) {
          inputDataLen = (short)(inputDataLen - macLength);
        }
      } else if(cipherAlg == KMType.RSA && padding == KMType.PADDING_NONE && mode == KMType.ENCRYPT ){
        // Length cannot be greater then key size according to JcardSim
        if(inputDataLen > 256) KMException.throwIt(KMError.INVALID_INPUT_LENGTH);
        // make input equal to 255 bytes
        //byte[] tmp = new byte[255];
        Util.arrayFillNonAtomic(tmpArray,(short)0,(short)256, (byte)0);
        Util.arrayCopyNonAtomic(
            inputDataBuf,
            inputDataStart,
            tmpArray, (short)(256 - inputDataLen),inputDataLen);
        inputDataStart = 0;
        inputDataLen = 256;
        inputDataBuf = tmpArray;

      } else if((cipherAlg == KMType.DES || cipherAlg == KMType.AES) && padding ==KMType.PKCS7 && mode == KMType.ENCRYPT){
        byte blkSize = 16;
        byte paddingBytes;
        short inputlen = inputDataLen;
        if (cipherAlg == KMType.DES) blkSize = 8;
        // padding bytes
        if (inputlen % blkSize == 0) paddingBytes = blkSize;
        else paddingBytes = (byte) (blkSize - (inputlen % blkSize));
        // final len with padding
        inputlen = (short) (inputlen + paddingBytes);
        // intermediate buffer to copy input data+padding
        //byte[] tmp = new byte[len];
        // fill in the padding
        Util.arrayFillNonAtomic(tmpArray, (short) 0, inputlen, paddingBytes);
        // copy the input data
        Util.arrayCopyNonAtomic(inputDataBuf,inputDataStart, tmpArray, (short)0, inputDataLen);
        inputDataBuf = tmpArray;
        inputDataLen = inputlen;
        inputDataStart = 0;
      }
      len = cipher.doFinal(inputDataBuf, inputDataStart, inputDataLen, outputDataBuf, outputDataStart);
      // JCOPProvider removes leading zeros during decryption in case of no padding - so add that back.
      if (cipherAlg == KMType.RSA && padding == KMType.PADDING_NONE && mode == KMType.DECRYPT && len < 256) {
        //byte[] tempBuf = new byte[256];
        //TODO Is this code required ??
        /*Util.arrayFill(tmpArray, (short) 0, (short) 256, (byte) 0);
        Util.arrayCopy(outputDataBuf, (short) 0, tmpArray, (short) (outputDataStart + 256 - len), len);
        Util.arrayCopy(tmpArray, (short) 0, outputDataBuf, outputDataStart, (short) 256);
        len = 256;*/
      } else if((cipherAlg == KMType.AES || cipherAlg == KMType.DES) // PKCS7
          && padding == KMType.PKCS7
          && mode == KMType.DECRYPT){
        byte blkSize = 16;
        if (cipherAlg == KMType.DES) blkSize = 8;
        if(len >0) {
          //verify if padding is corrupted.
          byte paddingByte = outputDataBuf[(short)(outputDataStart+len -1)];
          //padding byte always should be <= block size
          if((short)paddingByte > blkSize ||
              (short)paddingByte <= 0) KMException.throwIt(KMError.INVALID_ARGUMENT);
          len = (short)(len - (short)paddingByte);// remove the padding bytes
        }
      } else if(cipherAlg == KMType.AES && blockMode == KMType.GCM) {
        if(mode == KMType.ENCRYPT) {
          len += ((AEADCipher) cipher).retrieveTag(outputDataBuf, (short)(outputDataStart+len), macLength);  
        } else {
          boolean verified = ((AEADCipher) cipher).verifyTag(inputDataBuf, (short)(inputDataStart+inputDataLen), macLength, macLength);
          if(!verified) KMException.throwIt(KMError.VERIFICATION_FAILED);
        }
      }
    } finally {
      //KMJCOPSimProvider.getInstance().clean();
      KMJCOPSimProvider.getInstance().releaseCipherInstance(cipher);
      resetCipher();
    }
    return len;
  }

  //@Override
  public short sign(byte[] inputDataBuf, short inputDataStart, short inputDataLength, byte[] signBuf, short signStart) {
    short len = 0;
    try {
      len = signature.sign(inputDataBuf,inputDataStart,inputDataLength,signBuf,signStart);
    } finally {
        KMJCOPSimProvider.getInstance().releaseSignatureInstance(signature);
        signature = null;
    }
    return len;
  }

  //@Override
  public boolean verify(byte[] inputDataBuf, short inputDataStart, short inputDataLength, byte[] signBuf, short signStart, short signLength) {
    boolean ret = false;
    try {
      ret = signature.verify(inputDataBuf,inputDataStart,inputDataLength,signBuf,signStart,signLength);
    } finally {
      KMJCOPSimProvider.getInstance().releaseSignatureInstance(signature);
      signature = null;
    }
    return ret;
  }

  //@Override
  public void abort() {
    // do nothing
    if(cipher != null) {
      KMJCOPSimProvider.getInstance().releaseCipherInstance(cipher);
      cipher = null;
    }
    if(signature != null) {
      KMJCOPSimProvider.getInstance().releaseSignatureInstance(signature);
      signature = null;
    }
  }

  //@Override
  public void updateAAD(byte[] dataBuf, short dataStart, short dataLength) {
    ((AEADCipher) cipher).updateAAD(dataBuf, dataStart, dataLength);
  }

  //@Override
  public short getAESGCMOutputSize(short dataSize, short macLength) {
    if (mode == KMType.ENCRYPT) {
      return (short) (aesGcmUpdatedLen + dataSize + macLength);
    } else {
      return (short) (aesGcmUpdatedLen + dataSize - macLength);
    }
  }

  //@Override
  public void release() {
    KMJCOPSimProvider.getInstance().releaseOperationInstance(this);
  }

}
