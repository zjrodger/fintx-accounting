package org.fintx.accounting.service.impl;

import org.fintx.accounting.constant.AccountsSide;
import org.fintx.accounting.constant.OperationSymbol;
import org.fintx.accounting.constant.Operator;
import org.fintx.accounting.constant.TransactionFlag;
import org.fintx.accounting.constant.TransactionSymbol;
import org.fintx.accounting.entity.Account;
import org.fintx.accounting.entity.CodeOfAccounts;
import org.fintx.accounting.entity.CustomerAccountNo;
import org.fintx.accounting.entity.OperationEntry;
import org.fintx.accounting.entity.TransactionEntry;
import org.fintx.accounting.repository.AccountRepo;
import org.fintx.accounting.repository.CodeOfAccountsRepo;
import org.fintx.accounting.repository.CustomerAccountNoRepo;
import org.fintx.accounting.repository.CustomerAccountSnRepo;
import org.fintx.accounting.repository.InnerAccountNoRepo;
import org.fintx.accounting.repository.InnerAccountSnRepo;
import org.fintx.accounting.repository.OperationEntryRepo;
import org.fintx.accounting.repository.TransactionEntryRepo;
import org.fintx.accounting.service.DetailLedgerService;
import org.fintx.accounting.service.Operation;
import org.fintx.accounting.service.Transaction;
import org.fintx.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DetailLedgerServiceImpl implements DetailLedgerService {
    @Autowired
    private AccountRepo accountDao;
    @Autowired
    private TransactionEntryRepo transactionEntryDao;

    @Autowired
    private OperationEntryRepo operationEntryDao;
    @Autowired
    private CustomerAccountSnRepo customerAccountSnDao;
    @Autowired
    private InnerAccountSnRepo innerAccountSnDao;
    @Autowired
    private CustomerAccountNoRepo customerAccountNoDao;
    @Autowired
    private InnerAccountNoRepo innerAccountNoDao;

    @Autowired
    private CodeOfAccountsRepo codeOfAccountsDao;

    /*
     * 冲正是否可为负
     *
     */
    // TODO 放入账户控制标识中
    public static Boolean flash_not_negative = false;

    /*
     * 是否验证昨日余额
     * 
     */
    // TODO 放入账户控制标识中
    public static Boolean check_last_balance = false;

    @Override
    public void post(Transaction transaction) {
        // TODO Auto-generated method stub

    }

    @Override
    public void post(Operation operation) {
        // TODO Auto-generated method stub

    }

    @Override
    public Account auditAccount(String codeOfAccounts, String accountNo) {

        return accountDao.getByAccountNo(accountNo);
    }

    @Override
    public List<TransactionEntry> auditTransaction(String codeOfAccounts, LocalDate date, String accountNo, TransactionFlag[] flag, TransactionSymbol[] symbol,
            String businessId) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<OperationEntry> auditOperation(String codeOfAccounts, LocalDate date, String accountNo, OperationSymbol[] symbol, String businessId) {
        // TODO Auto-generated method stub
        return null;
    }

    private Account updateBalance(TransactionEntry entry) {
        // TODO 余额为负时，冲正报余额不足
        LocalDate txnDate = entry.getTransactionDate();// 渠道账期

        // 获取分户账
        Account currentAccount = accountDao.lockAccount(entry.getAccountNo());

        if (currentAccount == null) {
            // 返回
            throw new RuntimeException("No account found with accountNo:" + entry.getAccountNo());
        }
        // 获取各项金额
        BigDecimal balance = currentAccount.getBalance();
        BigDecimal frozenAmt = currentAccount.getFrozenAmt();
        BigDecimal newBalance = new BigDecimal("0.00");
        LocalDate latestTxnDate = currentAccount.getLatestTransDate();// 最终交易日期
        BigDecimal txnAmt = entry.getAmount();// 交易金额
        BigDecimal drTxnAmt = currentAccount.getDrTransAmt();// 借发生额
        BigDecimal crTxnAmt = currentAccount.getCrTransAmt();// 贷发生额
        BigDecimal drBalance = currentAccount.getDrBalance();
        BigDecimal crBalance = currentAccount.getCrBalance();
        entry.setBalanceAccum(new BigDecimal("0.00"));
        Operator operator = getOperatorBySymbolAndSide(entry.getSymbol(), codeOfAccountsDao.getByAccountsCodeNo(entry.getAccountsCodeNo()).getAccountsSide());

        // 处理冻结金额为负的情况，负数的冻结金额是异常情况，但是不影响交易
        if (frozenAmt.compareTo(BigDecimal.ZERO) < 0) {
            frozenAmt = new BigDecimal("0.00");
        }
        // 判断余额是加还是减，加负和减的时候要判断余额是否充足
        if (Operator.PLUS.equals(operator)) {
            // 冲正的情况交易金额为负数，此时要检查余额是否足够冲正
            if (txnAmt.compareTo(BigDecimal.ZERO) < 0) {
                // 先判断余额是否足够做交易,并且标志不允许余额为负
                if (flash_not_negative && balance.subtract(frozenAmt).add(txnAmt).compareTo(BigDecimal.ZERO) < 0
                        && "1".equals(currentAccount.getAccountCtrl().substring(2, 3))) {
                    // 冲正余额不足，返回
                    throw new RuntimeException("The balance is not enough for this flash transaction!");
                    // throw new TransactionException(BCModules.ACCOUNT_LEDGER, LedgersMsg.BC11000007E.getCode(), LedgersMsg.BC11000007E.getDesc());
                }
            }
            newBalance = balance.add(txnAmt);// 余额加交易金额
        } else if (Operator.MINUS.equals(operator)) {
            // 先判断余额是否足够做交易,并且标志不允许余额为负
            if (balance.subtract(frozenAmt).subtract(txnAmt).compareTo(BigDecimal.ZERO) < 0 && "1".equals(currentAccount.getAccountCtrl().substring(2, 3))) {
                // 余额不足做交易，返回
                throw new RuntimeException("The balance is not enough for this transaction!");
                // throw new TransactionException(BCModules.ACCOUNT_LEDGER, LedgersMsg.BC11000001E.getCode(), LedgersMsg.BC11000001E.getDesc());
            }

            newBalance = balance.subtract(txnAmt);
        }
        // 流水表余额记录为交易后余额
        entry.setBalance(newBalance);
        Account account = new Account();
        account.setAccountNo(entry.getAccountNo());
        // 金额足够做交易，判断是借还是贷
        if (latestTxnDate != null || !"".equals(latestTxnDate)) {
            if (latestTxnDate.until(txnDate).getDays() == 1) { // Dates.getDayGap(latestTxnDate, txnDate)
                                                               // 上次交易日期是账期前一天，借贷发生额抄到昨日借贷发生额，冻结金额抄录到昨日冻结金额，冻结金额不变，交易为今天此账户第一笔交易,借方或贷方发生额为交易金额
                account.setLastBalance(balance);
                account.setLastDrBalance(drBalance);
                account.setLastDrTransAmt(drTxnAmt);
                account.setLastCrBalance(crBalance);
                account.setLastCrTransAmt(crTxnAmt);
                account.setLatestTransDate(txnDate);
                account.setBalance(newBalance);
                // 冻结金额不变
                // accountA.setFrozenamt(frozenAmt);
                if (TransactionSymbol.DEBIT.equals(entry.getSymbol())) {
                    // accountA.setDrTransAmt(drTxnAmt);
                    account.setDrTransAmt(txnAmt);
                    account.setCrTransAmt(BigDecimal.ZERO);
                    account.setDrBalance(currentAccount.getDrBalance().add(txnAmt));
                } else {
                    account.setDrTransAmt(BigDecimal.ZERO);
                    // accountA.setCrTransAmt(crTxnAmt);
                    account.setCrTransAmt(txnAmt);
                    // 不变
                    // accountA.setDrBalance(bean.getDrBalance());
                }
            } else if (latestTxnDate.until(txnDate).getDays() > 1) {// Dates.getDayGap(latestTxnDate, txnDate)
                                                                    // 上次交易日期在账期前一天之前，昨日借贷发生额清零，冻结金额抄录到昨日冻结金额，交易为今天此账户第一笔交易,借方或贷方发生额为交易金额
                account.setLastBalance(balance);
                account.setLastDrBalance(drBalance);
                account.setLastDrTransAmt(BigDecimal.ZERO);
                account.setLastCrTransAmt(BigDecimal.ZERO);
                account.setLatestTransDate(txnDate);
                account.setBalance(newBalance);

                if (TransactionSymbol.DEBIT.equals(entry.getSymbol())) {
                    account.setDrTransAmt(txnAmt);
                    account.setCrTransAmt(BigDecimal.ZERO);
                    account.setDrBalance(currentAccount.getDrBalance().add(txnAmt));
                } else {
                    account.setDrTransAmt(BigDecimal.ZERO);
                    account.setCrTransAmt(txnAmt);
                    // 不变
                    // accountA.setDrBalance(bean.getDrBalance());
                }
            } else if (latestTxnDate.until(txnDate).getDays() == 0) {// Dates.getDayGap(latestTxnDate, txnDate) 上次交易日期为同日，交易不是当天第一笔，交易金额累计进借方或贷方发生额
                account.setBalance(newBalance);
                if (TransactionSymbol.DEBIT.equals(entry.getSymbol())) {
                    account.setDrTransAmt(drTxnAmt.add(txnAmt));
                    account.setDrBalance(currentAccount.getDrBalance().add(txnAmt));
                } else {
                    account.setCrTransAmt(crTxnAmt.add(txnAmt));
                    // 不变
                    // accountA.setDrBalance(bean.getDrBalance());
                }
            } else if (latestTxnDate.until(txnDate).getDays() == -1) {// Dates.getDayGap(latestTxnDate, txnDate)上次交易日期为渠道账期后一天,此交易肯定不是上次交易日期为账期后一天第一笔交易，此时昨日余额
                                                                      // 昨日发生额
                                                                      // 昨日积数，肯定都是正确的，所以不用修正（抄录）
                account.setBalance(newBalance);
                BigDecimal newLastBal = new BigDecimal("0.00");
                // BigDecimal lastFrozenAmt = currentAccount.getLastfrozenamt();
                // // 处理昨日冻结金额为负的情况，负数的冻结金额是异常情况，但是不影响交易
                // if (lastFrozenAmt.compareTo(BigDecimal.ZERO) < 0) {
                // lastFrozenAmt = new BigDecimal("0.00");
                // }
                BigDecimal lastBal = currentAccount.getLastBalance();
                if (Operator.PLUS.equals(operator)) {
                    // 冲正的情况交易金额为负数，此时要检查余额是否足够冲正
                    if (txnAmt.compareTo(BigDecimal.ZERO) < 0) {
                        // 检查昨日余额，并且账户不允许透支,断昨日余额是否足够做交易
                        if (flash_not_negative && "1".equals(currentAccount.getAccountCtrl().substring(2, 3))
                                && lastBal.subtract(frozenAmt).add(txnAmt).compareTo(BigDecimal.ZERO) < 0) {
                            // 昨日冲正余额不足，返回
                            throw new RuntimeException("The lastBalance is not enough for this flash transaction!");
                            // throw new
                            // TransactionException(BCModules.ACCOUNT_LEDGER,LedgersMsg.BC11000010E.getCode(),LedgersMsg.BC11000010E.getDesc());
                        }
                    }
                    newLastBal = lastBal.add(txnAmt);
                } else {
                    // 检查昨日余额，并且账户不允许透支 断昨日余额是否足够做交易
                    if (check_last_balance && "1".equals(currentAccount.getAccountCtrl().substring(2, 3))
                            && lastBal.subtract(frozenAmt).subtract(txnAmt).compareTo(BigDecimal.ZERO) < 0) {
                        // 昨日交易余额不足做交易，返回

                        throw new RuntimeException("The lastBalance is not enough for this transaction!");
                    }
                    newLastBal = lastBal.subtract(txnAmt);
                }

                account.setLastBalance(newLastBal);

                BigDecimal lastDrTxnAmt = currentAccount.getLastDrTransAmt();
                BigDecimal lastCrTxnAmt = currentAccount.getLastCrTransAmt();
                if (TransactionSymbol.DEBIT.equals(entry.getSymbol())) {
                    account.setLastDrTransAmt(lastDrTxnAmt.add(txnAmt));
                    account.setLastDrBalance(currentAccount.getLastDrBalance().add(txnAmt));
                } else {
                    account.setLastCrTransAmt(lastCrTxnAmt.add(txnAmt));
                    // 不变
                    // accountA.setDrBalance(bean.getDrBalance());
                }

                // TODO
                // 补一笔不入账的最终交易日期的账，备注下昨日账不然余额会突然减少造成误解//检查伟荣日终的逻辑是否能满足这种情况//和伟荣核对透支标志是
                // 2还是0？？？？？？？？？？//科目明细的唯一键为
                // 流水号加入账标志！！！！！！！//去掉科目明细
                // 日切时间不用修改---不用
                TransactionEntry latestDetail = (TransactionEntry) Objects.deepClone(entry);
                // 这里还是使用渠道账期，用于表外区分昨日账，因为表外counted始终为2
                // latestDetail.setTxndate(latestTxnDate);
                latestDetail.setEffective(false);
                latestDetail.setComment("发生了昨日账务");
                Map<String, Object> latestCoreMap = new HashMap<String, Object>();
                latestCoreMap.put("tableName", "t_det_core_" + latestTxnDate);
                latestCoreMap.put("detail", latestDetail);
                transactionEntryDao.recordDetailAccount(latestCoreMap);
                Map<String, Object> latestAcctTitleMap = new HashMap<String, Object>();
                latestAcctTitleMap.put("tableName", "t_det_" + latestDetail.getAccountsCodeNo());
                latestAcctTitleMap.put("detail", latestDetail);
                transactionEntryDao.recordDetailAccount(latestAcctTitleMap);
                entry.setBalance(newLastBal);// 计入昨天的流水余额为昨日余额最终值
            } else {// 上次交易日期在账期后一天之后（跨两日及以上），此为账期异常
                    // 账期异常
                throw new RuntimeException("txnDate:" + txnDate + " invalid while latestTransactionDate is " + latestTxnDate);
            }

        }

        transactionEntryDao.save(entry);
        // TODO move to save...
        // Map<String, Object> acctTitleMap = new HashMap<String, Object>();
        // acctTitleMap.put("tableName", "t_det_" + entry.getAccttitlecode());
        // acctTitleMap.put("detail", entry);
        // transactionEntryDao.recordDetailAccount(acctTitleMap);
        accountDao.modifyBalance(account);
        return account;
    }

    // @Override
    // public void updateBalanceBA(TransactionEntry detail) throws CoreException, Exception {
    // String txnDate = detail.getTxndate();// 渠道账期
    // // 获取分户账表名
    // String tableName = SysInitService.getAcctTableName(detail.getAccttitlecode());
    // Map<String, Object> map4Update = new HashMap<String, Object>();
    // map4Update.put("tableName", tableName);
    // map4Update.put("acctno", detail.getAccountNo());
    // // 流水表余额记录为交易后余额
    // detail.setBalance(BigDecimal.ZERO);
    // detail.setBalanceAccum(BigDecimal.ZERO);
    // Account accountA = new Account();
    // accountA.setAccountNo(detail.getAccountNo());
    //
    // Map<String, Object> coreMap = new HashMap<String, Object>();
    // coreMap.put("tableName", "t_det_core_" + txnDate);
    // coreMap.put("detail", detail);
    // transactionEntryDao.recordDetailAccount(coreMap);
    // Map<String, Object> acctTitleMap = new HashMap<String, Object>();
    // acctTitleMap.put("tableName", "t_det_" + detail.getAccttitlecode());
    // acctTitleMap.put("detail", detail);
    // transactionEntryDao.recordDetailAccount(acctTitleMap);
    // }

    public void freeze(OperationEntry entry) {
        accountDao.plusToFrozenAmt(entry.getAmount());
        operationEntryDao.save(entry);
    }

    public void release(OperationEntry entry) {
        accountDao.minusToFrozenAmt(entry.getAmount());
        operationEntryDao.save(entry);
    }

    public void control(OperationEntry entry) {
        accountDao.minusToFrozenAmt(entry.getAmount());
        operationEntryDao.save(entry);
    }

    public void close(OperationEntry entry) {
        accountDao.minusToFrozenAmt(entry.getAmount());
        operationEntryDao.save(entry);
    }

    public void lock(OperationEntry entry) {
        accountDao.minusToFrozenAmt(entry.getAmount());
        operationEntryDao.save(entry);
    }

    public void free(OperationEntry entry) {
        accountDao.minusToFrozenAmt(entry.getAmount());
        operationEntryDao.save(entry);
    }

    /**
     * Get plus and minus operator from TransactionSymbol and AccountSide
     * 
     * @param symbol TransactionSymbol
     * @param side AccountSide
     * @return Operator the plus or minus
     */
    public Operator getOperatorBySymbolAndSide(TransactionSymbol symbol, AccountsSide side) {
        if (symbol.equals(TransactionSymbol.DEBIT)) {
            if (side.equals(AccountsSide.DEBTOR)) {
                return Operator.PLUS;
            } else {
                return Operator.MINUS;
            }

        } else if (symbol.equals(TransactionSymbol.CREDIT)) {
            if (side.equals(AccountsSide.CREDITOR)) {
                return Operator.PLUS;
            } else {
                return Operator.MINUS;
            }
        } else if (symbol.equals(TransactionSymbol.RECEIPT)) {
            return Operator.PLUS;
        } else {
            return Operator.MINUS;
        }
    }
}
