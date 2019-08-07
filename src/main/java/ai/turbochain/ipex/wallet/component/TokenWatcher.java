package ai.turbochain.ipex.wallet.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.utils.Convert;

import ai.turbochain.ipex.wallet.entity.Account;
import ai.turbochain.ipex.wallet.entity.Coin;
import ai.turbochain.ipex.wallet.entity.Contract;
import ai.turbochain.ipex.wallet.entity.Deposit;
import ai.turbochain.ipex.wallet.event.DepositEvent;
import ai.turbochain.ipex.wallet.service.AccountService;
import ai.turbochain.ipex.wallet.service.EthService;
import ai.turbochain.ipex.wallet.util.EthConvert;

@Component
public class TokenWatcher extends Watcher {
	private Logger logger = LoggerFactory.getLogger(TokenWatcher.class);
	@Autowired
	private Web3j web3j;
	@Autowired
	private Contract contract;
	@Autowired
	private AccountService accountService;
	@Autowired
	private Coin coin;
	@Autowired
	private ExecutorService executorService;
	@Autowired
	private EthService ethService;
	@Autowired
	private DepositEvent depositEvent;

	public List<Deposit> replayBlock(Long startBlockNumber, Long endBlockNumber) {
		List<Deposit> deposits = new ArrayList<>();
		for (Long blockHeight = startBlockNumber; blockHeight <= endBlockNumber; blockHeight++) {
			EthBlock block = null;
			try {
				logger.info("ethGetBlockByNumber {}", blockHeight);
				block = web3j.ethGetBlockByNumber(new DefaultBlockParameterNumber(blockHeight), true).send();
			} catch (IOException e) {
				e.printStackTrace();
			}
			List<EthBlock.TransactionResult> transactionResults = block.getBlock().getTransactions();
			logger.info("transactionCount {}", transactionResults.size());
			for (EthBlock.TransactionResult transactionResult : transactionResults) {
				EthBlock.TransactionObject transactionObject = (EthBlock.TransactionObject) transactionResult;
				Transaction transaction = transactionObject.get();
				try {
					EthGetTransactionReceipt receipt = web3j.ethGetTransactionReceipt(transaction.getHash()).send();
					if (receipt.getTransactionReceipt().get() != null) {
						String input = transaction.getInput();
						String cAddress = transaction.getTo();
						if (StringUtils.isNotEmpty(input) && input.length() >= 138
								&& contract.getAddress().equalsIgnoreCase(cAddress)) {
							String data = input.substring(0, 9);
							data = data + input.substring(17, input.length());
							Function function = new Function("transfer", Arrays.asList(),
									Arrays.asList(new TypeReference<Address>() {
									}, new TypeReference<Uint256>() {
									}));

							List<Type> params = FunctionReturnDecoder.decode(data, function.getOutputParameters());
							// 充币地址
							String toAddress = params.get(0).getValue().toString();
							String amount = params.get(1).getValue().toString();
							if (accountService.isAddressExist(toAddress)) {
								if (StringUtils.isNotEmpty(amount)) {
									Deposit deposit = new Deposit();
									deposit.setTxid(transaction.getHash());
									deposit.setBlockHash(transaction.getBlockHash());
									deposit.setAmount(Convert.fromWei(amount, Convert.Unit.ETHER));						
									deposit.setAddress(toAddress);
									deposit.setTime(Calendar.getInstance().getTime());
									logger.info("receive {} {}", deposit.getAmount(), getCoin().getUnit());
									deposit.setBlockHeight(transaction.getBlockNumber().longValue());
									deposits.add(deposit);
									afterDeposit(deposit);
								}
							}
						}
					}
				} catch (IOException e) {
					e.printStackTrace();
				}

			}
		}
		return deposits;
	}

	/**
	 * 充值成功后的操作
	 */
	public void afterDeposit(Deposit deposit) {
		executorService.execute(new Runnable() {
			public void run() {
				depositCoin(deposit);
			}
		});
	}

	/**
	 * 充值PWR代币转账到withdraw账户
	 * 
	 * @param deposit
	 */
	public void depositCoin(Deposit deposit) {
		try {
			BigDecimal fee = ethService.getMinerFee(contract.getGasLimit());
			Account account = accountService.findByAddress(deposit.getAddress());
			if (ethService.getBalance(account.getAddress()).compareTo(fee) < 0) {
				logger.info("地址{}手续费不足，最低为{}PWR", account.getAddress(), fee);
				ethService.transfer(coin.getKeystorePath() + "/" + coin.getWithdrawWallet(),
						coin.getWithdrawWalletPassword(), deposit.getAddress(), fee, true, "");
				Thread.sleep(1000 * 60 * 15);// 给充值地址转PWR作为手续费，15分钟交易确认
				logger.info("{}手续费不足，转账PWR到充值账户作为手续费:from={},to={},amount={},sync={}", deposit.getAddress(),
						coin.getWithdrawAddress(), deposit.getAddress(), fee, true);
			}
			logger.info("充值PWR代币转账到withdraw账户:from={},to={},amount={},sync={},withdrawId={}", deposit.getAddress(),
					coin.getWithdrawAddress(), deposit.getAmount(), true, "");
			ethService.transferToken(deposit.getAddress(), coin.getWithdrawAddress(), deposit.getAmount(), true);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public synchronized void replayBlockInit(Long startBlockNumber, Long endBlockNumber) {
		for (long i = startBlockNumber; i <= endBlockNumber; i++) {
			EthBlock block = null;
			try {
				logger.info("ethGetBlockByNumber {}", i);
				block = web3j.ethGetBlockByNumber(new DefaultBlockParameterNumber(i), true).send();
			} catch (IOException e) {
				e.printStackTrace();
			}
			List<EthBlock.TransactionResult> transactionResults = block.getBlock().getTransactions();
			logger.info("transactionCount {}", transactionResults.size());
			transactionResults.forEach(transactionResult -> {

				EthBlock.TransactionObject transactionObject = (EthBlock.TransactionObject) transactionResult;
				Transaction transaction = transactionObject.get();
				try {
					EthGetTransactionReceipt receipt = web3j.ethGetTransactionReceipt(transaction.getHash()).send();
					if (receipt.getTransactionReceipt().get() != null) {
						String input = transaction.getInput();
						String cAddress = transaction.getTo();
						if (StringUtils.isNotEmpty(input) && input.length() >= 138
								&& contract.getAddress().equalsIgnoreCase(cAddress)) {
							String data = input.substring(0, 9);
							data = data + input.substring(17, input.length());
							Function function = new Function("transfer", Arrays.asList(),
									Arrays.asList(new TypeReference<Address>() {
									}, new TypeReference<Uint256>() {
									}));

							List<Type> params = FunctionReturnDecoder.decode(data, function.getOutputParameters());
							// 充币地址
							String toAddress = params.get(0).getValue().toString();
							String amount = params.get(1).getValue().toString();
							logger.info("################{}###################{}", toAddress, amount);
							if (accountService.isAddressExist(toAddress)) {
								if (StringUtils.isNotEmpty(amount)) {
									Deposit deposit = new Deposit();
									deposit.setTxid(transaction.getHash());
									deposit.setBlockHash(transaction.getBlockHash());
									deposit.setAmount(EthConvert.fromWei(amount, contract.getUnit()));
									deposit.setAddress(toAddress);
									deposit.setTime(Calendar.getInstance().getTime());
									deposit.setBlockHeight(transaction.getBlockNumber().longValue());
									depositEvent.onConfirmed(deposit);
								}
							}
						}
					}
				} catch (IOException e) {
					e.printStackTrace();
				}

			});
		}
	}

	@Override
	public Long getNetworkBlockHeight() {
		try {
			EthBlockNumber blockNumber = web3j.ethBlockNumber().send();
			long networkBlockNumber = blockNumber.getBlockNumber().longValue();
			return networkBlockNumber;
		} catch (Exception e) {
			e.printStackTrace();
			return 0L;
		}
	}
}
