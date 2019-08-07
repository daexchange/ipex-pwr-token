package ai.turbochain.ipex.wallet.job;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ai.turbochain.ipex.wallet.entity.Coin;
import ai.turbochain.ipex.wallet.entity.Contract;
import ai.turbochain.ipex.wallet.service.AccountService;
import ai.turbochain.ipex.wallet.service.EthService;
import ai.turbochain.ipex.wallet.util.AccountReplay;
import ai.turbochain.ipex.wallet.util.MessageResult;

@Component
public class CoinCollectJob {
	private Logger logger = LoggerFactory.getLogger(CoinCollectJob.class);
	@Autowired
	private AccountService accountService;
	@Autowired
	private EthService ethService;
	@Autowired
	private Coin coin;
	@Autowired
	private Contract contract;

	/**
	 * 为用户地址充值矿工费(每三天执行一次)
	 */
	// @Scheduled(cron = "0 0 0 1/3 * *")
	public void rechargeMinerFee() {
		try {
			AccountReplay accountReplay = new AccountReplay(accountService, 100);
			BigDecimal minerFee = ethService.getMinerFee(contract.getGasLimit());

			accountReplay.run(account -> {
				try {
					BigDecimal ethBalance = ethService.getBalance(account.getAddress());
					BigDecimal tokenBalance = ethService.getTokenBalance(account.getAddress());
					// 给满足条件的地址充矿工费，条件1：eth额度小于minerFee,条件2:balance大于等于minCollectAmount
					if (ethBalance.compareTo(minerFee) < 0 && tokenBalance.compareTo(coin.getMinCollectAmount()) >= 0) {
						logger.info("process account:{}", account);
						// 计算本次要转的矿工费
						BigDecimal feeAmt = minerFee.subtract(ethBalance);

						MessageResult mr = ethService.transferFromWithdrawWallet(account.getAddress(), feeAmt, false,
								"");
						logger.info("transfer fee {},result:{}", feeAmt, mr);
						if (mr.getCode() == 0) {
							ethBalance = minerFee;
						}
					}
					// 同步账户余额
					accountService.updateBalanceAndGas(account.getAddress(), tokenBalance, ethBalance);
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
