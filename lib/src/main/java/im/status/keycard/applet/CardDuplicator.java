package im.status.keycard.applet;

import im.status.keycard.io.APDUException;
import im.status.keycard.io.WrongPINException;

import java.io.IOException;
import java.security.SecureRandom;

/**
 * Class helping with the card duplication process. Depending on the client's role, only some of the methods are relevant.
 */
public class CardDuplicator {
  private byte[] secret;
  private KeycardCommandSet cmdSet;
  private DuplicatorCallback cb;

  /**
   * Creates a CardDuplicator object. Regardless of the role of the client, this object must be kept and used for the
   * entire duplication session. It cannot be reused for multiple sessions.
   * 
   * @param cmdSet the CommandSet to use
   * @param cb the callback object for backups. This is needed only on the client performing steps requiring pairing
   *           and authentication. Clients which only add entropy should pass null
   */
  public CardDuplicator(KeycardCommandSet cmdSet, DuplicatorCallback cb) {
    this.cmdSet = cmdSet;
    this.cb = cb;
    this.secret = new byte[32];
    SecureRandom random = new SecureRandom();
    random.nextBytes(this.secret);
  }

  /**
   * Creates a CardDuplicator object. Convenience version of the constructor without DuplicatorCallback argument.
   *
   * @param cmdSet the CommandSet to use
   */
  public CardDuplicator(KeycardCommandSet cmdSet) {
    this(cmdSet, null);
  }


  private void preamble() throws IOException, APDUException {
    ApplicationInfo appInfo = new ApplicationInfo(cmdSet.select().checkOK().getData());
    Pairing pairing = cb.getPairing(appInfo);

    if (pairing == null) {
      throw new APDUException("The given card is not paired");
    }

    cmdSet.setPairing(pairing);
    cmdSet.autoOpenSecureChannel();
    ApplicationStatus appStatus = new ApplicationStatus(cmdSet.getStatus(KeycardCommandSet.GET_STATUS_P1_APPLICATION).checkOK().getData());
    int remainingAttempts = appStatus.getPINRetryCount();

    while(remainingAttempts > 0) {
      try {
        cmdSet.verifyPIN(cb.getPIN(appInfo, remainingAttempts)).checkAuthOK();
        break;
      } catch(WrongPINException e) {
        remainingAttempts = e.getRetryAttempts();
      }
    }

    if (remainingAttempts <= 0) {
      throw new APDUException("Card blocked");
    }
  }

  /**
   * Starts duplication session. Must be used on all cards taking part of in the duplication process.
   *
   * @param clientCount the number of clients which will be adding entropy for the key, including this one
   *
   * @throws IOException communication error
   * @throws APDUException unexpected card response
   */
  public void startDuplication(int clientCount) throws IOException, APDUException {
    preamble();
    cmdSet.duplicateKeyStart(clientCount, secret).checkOK();
  }

  /**
   * Exports key. Must be used on the card designated as the source for the duplication.
   *
   * @throws IOException communication error
   * @throws APDUException unexpected card response
   */
  public byte[] exportKey() throws IOException, APDUException {
    preamble();
    return cmdSet.duplicateKeyExport().checkOK().getData();
  }

  /**
   * Imports key. Must be used on all cards designated as the target for the duplication.
   *
   * @param key the key to import
   * @return the key UID
   * @throws IOException communication error
   * @throws APDUException unexpected card response
   */
  public byte[] importKey(byte[] key) throws IOException, APDUException {
    preamble();
    return cmdSet.duplicateKeyImport(key).checkOK().getData();
  }

  /**
   * Adds entropy. Must be used on all cards taking part in the backup process. Each client taking part must use this
   * exactly once, except for the client which started the backup.
   *
   * @throws IOException communication error
   * @throws APDUException unexpected card response
   */
  public void addEntropy() throws IOException, APDUException {
    cmdSet.select().checkOK();
    cmdSet.duplicateKeyAddEntropy(secret).checkOK();
  }
}
