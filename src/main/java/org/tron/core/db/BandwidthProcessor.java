package org.tron.core.db;


import static org.tron.protos.Protocol.Transaction.Contract.ContractType.TransferAssetContract;

import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.config.Parameter.ChainConstant;
import org.tron.core.exception.ValidateBandwidthException;
import org.tron.protos.Contract.TransferAssetContract;
import org.tron.protos.Contract.TransferContract;
import org.tron.protos.Protocol.Transaction.Contract;

@Slf4j
public class BandwidthProcessor {

  private Manager dbManager;
  private long precision;
  private long windowSize;

  public BandwidthProcessor(Manager manager) {
    this.dbManager = manager;
    this.precision = ChainConstant.PRECISION;
    this.windowSize = ChainConstant.WINDOW_SIZE_MS / ChainConstant.BLOCK_PRODUCED_INTERVAL;
  }

  private long divideCeil(long numerator, long denominator) {
    return (numerator / denominator) + ((numerator % denominator) > 0 ? 1 : 0);
  }

  private long increase(long lastUsage, long usage, long lastTime, long now) {
    long averageLastUsage = divideCeil(lastUsage * precision, windowSize);
    long averageUsage = divideCeil(usage * precision, windowSize);

    if (lastTime != now) {
      assert now > lastTime;
      if (lastTime + windowSize > now) {
        long delta = now - lastTime;
        double decay = (windowSize - delta) / (double) windowSize;
        averageLastUsage = Math.round(averageLastUsage * decay);
      } else {
        averageLastUsage = 0;
      }
    }
    averageLastUsage += averageUsage;
    return getUsage(averageLastUsage);
  }

  private long getUsage(long usage) {
    return usage * windowSize / precision;
  }

  public void updateUsage(AccountCapsule accountCapsule) {
    long now = dbManager.getWitnessController().getHeadSlot();
    updateUsage(accountCapsule, now);
  }

  public void updateUsage(AccountCapsule accountCapsule, long now) {
    long oldNetUsage = accountCapsule.getNetUsage();
    long latestConsumeTime = accountCapsule.getLatestConsumeTime();
    accountCapsule.setNetUsage(increase(oldNetUsage, 0, latestConsumeTime, now));
    long oldFreeNetUsage = accountCapsule.getFreeNetUsage();
    long latestConsumeFreeTime = accountCapsule.getLatestConsumeFreeTime();
    accountCapsule.setFreeNetUsage(increase(oldFreeNetUsage, 0, latestConsumeFreeTime, now));
    Map<String, Long> assetMap = accountCapsule.getAssetMap();
    assetMap.forEach((assetName, balance) -> {
      long oldFreeAssetNetUsage = accountCapsule.getFreeAssetNetUsage(assetName);
      long latestAssetOperationTime = accountCapsule.getLatestAssetOperationTime(assetName);
      accountCapsule.putFreeAssetNetUsage(assetName,
          increase(oldFreeAssetNetUsage, 0, latestAssetOperationTime, now));
    });
  }

  public void consumeBandwidth(TransactionCapsule trx) throws ValidateBandwidthException {
    List<Contract> contracts =
        trx.getInstance().getRawData().getContractList();

    for (Contract contract : contracts) {
      long bytes = trx.getSerializedSize();
      byte[] address = TransactionCapsule.getOwner(contract);
      AccountCapsule accountCapsule = dbManager.getAccountStore().get(address);
      if (accountCapsule == null) {
        throw new ValidateBandwidthException("account not exists");
      }
      long now = dbManager.getWitnessController().getHeadSlot();

      if (contractCreateNewAccount(contract)) {
        consumeForCreateNewAccount(accountCapsule, now);
      }

      if (contract.getType() == TransferAssetContract) {
        if (useAssetAccountNet(contract, accountCapsule, now, bytes)) {
          continue;
        }
      }

      if (useAccountNet(accountCapsule, bytes, now)) {
        continue;
      }

      if (useFreeNet(accountCapsule, bytes, now)) {
        continue;
      }

      throw new ValidateBandwidthException("bandwidth is not enough");
    }
  }

  public void consumeForCreateNewAccount(AccountCapsule accountCapsule, long now)
      throws ValidateBandwidthException {
    long cost = ChainConstant.CREATE_NEW_ACCOUNT_COST;

    long netUsage = accountCapsule.getNetUsage();
    long latestConsumeTime = accountCapsule.getLatestConsumeTime();
    long netLimit = calculateGlobalNetLimit(accountCapsule.getFrozenBalance());

    long newNetUsage = increase(netUsage, 0, latestConsumeTime, now);

    if (cost <= (netLimit - newNetUsage)) {
      latestConsumeTime = now;
      newNetUsage = increase(newNetUsage, cost, latestConsumeTime, now);
      accountCapsule.setLatestConsumeTime(latestConsumeTime);
      accountCapsule.setNetUsage(newNetUsage);
      dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
    } else {
      throw new ValidateBandwidthException("bandwidth is not enough to create new account");
    }

  }

  public boolean contractCreateNewAccount(Contract contract) {
    AccountCapsule toAccount;
    switch (contract.getType()) {
      case TransferContract:
        TransferContract transferContract;
        try {
          transferContract = contract.getParameter().unpack(TransferContract.class);
        } catch (Exception ex) {
          throw new RuntimeException(ex.getMessage());
        }
        toAccount = dbManager.getAccountStore().get(transferContract.getToAddress().toByteArray());
        return toAccount == null;
      case TransferAssetContract:
        TransferAssetContract transferAssetContract;
        try {
          transferAssetContract = contract.getParameter().unpack(TransferAssetContract.class);
        } catch (Exception ex) {
          throw new RuntimeException(ex.getMessage());
        }
        toAccount = dbManager.getAccountStore()
            .get(transferAssetContract.getToAddress().toByteArray());
        return toAccount == null;
      default:
        return false;
    }
  }


  private boolean useAssetAccountNet(Contract contract, AccountCapsule accountCapsule, long now,
      long bytes)
      throws ValidateBandwidthException {

    ByteString assetName;
    try {
      assetName = contract.getParameter().unpack(TransferAssetContract.class).getAssetName();
    } catch (Exception ex) {
      throw new RuntimeException(ex.getMessage());
    }
    String assetNameString = ByteArray.toStr(assetName.toByteArray());
    AssetIssueCapsule assetIssueCapsule
        = dbManager.getAssetIssueStore().get(assetName.toByteArray());
    if (assetIssueCapsule == null) {
      throw new ValidateBandwidthException("asset not exists");
    }

    if (assetIssueCapsule.getOwnerAddress() == accountCapsule.getAddress()) {
      return useAccountNet(accountCapsule, bytes, now);
    }

    long publicFreeAssetNetLimit = assetIssueCapsule.getPublicFreeAssetNetLimit();
    long publicFreeAssetNetUsage = assetIssueCapsule.getPublicFreeAssetNetUsage();
    long publicLatestFreeNetTime = assetIssueCapsule.getPublicLatestFreeNetTime();

    long newPublicFreeAssetNetUsage = increase(publicFreeAssetNetUsage, 0,
        publicLatestFreeNetTime, now);

    if (bytes > (publicFreeAssetNetLimit - newPublicFreeAssetNetUsage)) {
      logger.debug("The " + assetNameString + " public free bandwidth is not enough");
      return false;
    }

    long freeAssetNetLimit = assetIssueCapsule.getFreeAssetNetLimit();

    long freeAssetNetUsage = accountCapsule
        .getFreeAssetNetUsage(assetNameString);
    long latestAssetOperationTime = accountCapsule
        .getLatestAssetOperationTime(assetNameString);

    long newFreeAssetNetUsage = increase(freeAssetNetUsage, 0,
        latestAssetOperationTime, now);

    if (bytes > (freeAssetNetLimit - newFreeAssetNetUsage)) {
      logger.debug("The " + assetNameString + " free bandwidth is not enough");
      return false;
    }

    AccountCapsule issuerAccountCapsule = dbManager.getAccountStore()
        .get(assetIssueCapsule.getOwnerAddress().toByteArray());

    long issuerNetUsage = issuerAccountCapsule.getNetUsage();
    long latestConsumeTime = issuerAccountCapsule.getLatestConsumeTime();
    long issuerNetLimit = calculateGlobalNetLimit(issuerAccountCapsule.getFrozenBalance());

    long newIssuerNetUsage = increase(issuerNetUsage, 0, latestConsumeTime, now);

    if (bytes > (issuerNetLimit - newIssuerNetUsage)) {
      logger.debug("The " + assetNameString + " issuer'bandwidth is not enough");
      return false;
    }

    latestConsumeTime = now;
    latestAssetOperationTime = now;
    publicLatestFreeNetTime = now;
    long latestOperationTime = dbManager.getHeadBlockTimeStamp();
    newIssuerNetUsage = increase(newIssuerNetUsage, bytes, latestConsumeTime, now);
    newFreeAssetNetUsage = increase(newFreeAssetNetUsage,
        bytes, latestAssetOperationTime, now);
    newPublicFreeAssetNetUsage = increase(newPublicFreeAssetNetUsage, bytes,
        publicLatestFreeNetTime, now);

    issuerAccountCapsule.setNetUsage(newIssuerNetUsage);
    issuerAccountCapsule.setLatestConsumeTime(latestConsumeTime);

    accountCapsule.setLatestOperationTime(latestOperationTime);
    accountCapsule.putLatestAssetOperationTimeMap(assetNameString,
        latestAssetOperationTime);
    accountCapsule.putFreeAssetNetUsage(assetNameString, newFreeAssetNetUsage);

    assetIssueCapsule.setPublicFreeAssetNetUsage(newPublicFreeAssetNetUsage);
    assetIssueCapsule.setPublicLatestFreeNetTime(publicLatestFreeNetTime);

    dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
    dbManager.getAccountStore().put(issuerAccountCapsule.createDbKey(),
        issuerAccountCapsule);
    dbManager.getAssetIssueStore().put(assetIssueCapsule.createDbKey(), assetIssueCapsule);

    return true;

  }

  public long calculateGlobalNetLimit(long frozeBalance) {
    long netWeight = frozeBalance / 1000_000L;
    long totalNetLimit = dbManager.getDynamicPropertiesStore().getTotalNetLimit();
    long totalNetWeight = dbManager.getDynamicPropertiesStore().getTotalNetWeight();
    return netWeight * totalNetLimit / totalNetWeight;
  }

  private boolean useAccountNet(AccountCapsule accountCapsule, long bytes, long now) {

    long netUsage = accountCapsule.getNetUsage();
    long latestConsumeTime = accountCapsule.getLatestConsumeTime();
    long netLimit = calculateGlobalNetLimit(accountCapsule.getFrozenBalance());

    long newNetUsage = increase(netUsage, 0, latestConsumeTime, now);

    if (bytes > (netLimit - newNetUsage)) {
      logger.debug("net usage is running out. now use free net usage");
      return false;
    }

    latestConsumeTime = now;
    long latestOperationTime = dbManager.getHeadBlockTimeStamp();
    newNetUsage = increase(newNetUsage, bytes, latestConsumeTime, now);
    accountCapsule.setNetUsage(newNetUsage);
    accountCapsule.setLatestOperationTime(latestOperationTime);
    accountCapsule.setLatestConsumeTime(latestConsumeTime);

    dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
    return true;
  }

  private boolean useFreeNet(AccountCapsule accountCapsule, long bytes, long now) {

    long freeNetLimit = dbManager.getDynamicPropertiesStore().getFreeNetLimit();
    long freeNetUsage = accountCapsule.getFreeNetUsage();
    long latestConsumeFreeTime = accountCapsule.getLatestConsumeFreeTime();
    long newFreeNetUsage = increase(freeNetUsage, 0, latestConsumeFreeTime, now);

    if (bytes > (freeNetLimit - newFreeNetUsage)) {
      logger.debug("free net usage is running out");
      return false;
    }

    long publicNetLimit = dbManager.getDynamicPropertiesStore().getPublicNetLimit();
    long publicNetUsage = dbManager.getDynamicPropertiesStore().getPublicNetUsage();
    long publicNetTime = dbManager.getDynamicPropertiesStore().getPublicNetTime();

    long newPublicNetUsage = increase(publicNetUsage, 0, publicNetTime, now);

    if (bytes > (publicNetLimit - newPublicNetUsage)) {
      logger.debug("free public net usage is running out");
      return false;
    }

    latestConsumeFreeTime = now;
    long latestOperationTime = dbManager.getHeadBlockTimeStamp();
    publicNetTime = now;
    newFreeNetUsage = increase(newFreeNetUsage, bytes, latestConsumeFreeTime, now);
    newPublicNetUsage = increase(newPublicNetUsage, bytes, publicNetTime, now);
    accountCapsule.setFreeNetUsage(newFreeNetUsage);
    accountCapsule.setLatestConsumeFreeTime(latestConsumeFreeTime);
    accountCapsule.setLatestOperationTime(latestOperationTime);

    dbManager.getDynamicPropertiesStore().savePublicNetUsage(newPublicNetUsage);
    dbManager.getDynamicPropertiesStore().savePublicNetTime(publicNetTime);
    dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
    return true;

  }
}

