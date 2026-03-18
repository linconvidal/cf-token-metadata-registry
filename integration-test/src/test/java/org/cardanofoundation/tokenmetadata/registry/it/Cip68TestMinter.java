package org.cardanofoundation.tokenmetadata.registry.it;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import com.bloxbean.cardano.client.cip.cip68.CIP68FT;
import com.bloxbean.cardano.client.cip.cip68.CIP68ReferenceToken;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import lombok.extern.slf4j.Slf4j;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Utility to mint CIP-68 token pairs (reference NFT + user FT) on a yaci devnet.
 * Uses an always-true PlutusV2 script as the minting policy — simplest approach
 * that works with the CIP-68 minting API (ScriptTx.mintAsset with inline datum).
 */
@Slf4j
public class Cip68TestMinter {

    private static final String DEVKIT_ADMIN_BASE_URL = "http://localhost:10000/";

    // Always-true PlutusV2 script — effectively no validation, just like a NativeScript
    private static final PlutusV2Script ALWAYS_TRUE_SCRIPT = PlutusV2Script.builder()
            .type("PlutusScriptV2")
            .cborHex("49480100002221200101")
            .build();

    private final BackendService backendService;
    private final Account senderAccount;

    public Cip68TestMinter(String yaciStoreUrl) {
        this.backendService = new BFBackendService(yaciStoreUrl, "Dummy");
        this.senderAccount = new Account(Networks.testnet());
    }

    public record MintResult(String policyId, String assetNameHex, String txHash) {}

    /**
     * Mints a CIP-68 FT token pair on the devnet:
     * - Reference NFT (label 100 / prefix 000643b0) with inline datum containing metadata
     * - User FT (label 333 / prefix 0014df10) with specified quantity
     */
    public MintResult mintCip68FungibleToken(String tokenName, String description,
                                              String ticker, int decimals,
                                              long userTokenQty) throws Exception {
        var senderAddress = senderAccount.baseAddress();

        // Fund the sender
        topUpFund(senderAddress, 50000);
        Thread.sleep(5000); // Wait for topup to be processed

        // Build CIP-68 FT metadata using the high-level API
        CIP68FT ft = CIP68FT.create()
                .name(tokenName)
                .description(description)
                .ticker(ticker)
                .decimals(decimals);

        CIP68ReferenceToken referenceToken = ft.getReferenceToken();
        Asset userToken = ft.getAsset(BigInteger.valueOf(userTokenQty));
        Asset refToken = referenceToken.getAsset(BigInteger.ONE);
        PlutusData datum = referenceToken.getDatumAsPlutusData();

        // Reference token goes to a script address (standard CIP-68 pattern)
        String referenceTokenReceiver = AddressProvider.getEntAddress(ALWAYS_TRUE_SCRIPT, Networks.testnet()).toBech32();

        // User token goes to sender
        String userTokenReceiver = senderAddress;

        // Build minting transaction
        ScriptTx scriptTx = new ScriptTx()
                .mintAsset(ALWAYS_TRUE_SCRIPT, List.of(refToken), PlutusData.unit(), referenceTokenReceiver, datum)
                .mintAsset(ALWAYS_TRUE_SCRIPT, List.of(userToken), PlutusData.unit(), userTokenReceiver);

        QuickTxBuilder quickTxBuilder = new QuickTxBuilder(backendService);
        Result<String> result = quickTxBuilder.compose(scriptTx)
                .feePayer(senderAddress)
                .withSigner(SignerProviders.signerFrom(senderAccount))
                .completeAndWait(txHash -> log.info("CIP-68 FT mint tx submitted: {}", txHash));

        if (!result.isSuccessful()) {
            throw new RuntimeException("Failed to mint CIP-68 token: " + result.getResponse());
        }

        String policyId = ALWAYS_TRUE_SCRIPT.getPolicyId();
        // getAssetNameAsHex() returns "0x0014df10..." — strip the "0x" prefix for our subject format
        String ftAssetNameHex = ft.getAssetNameAsHex().replaceFirst("^0x", "");

        log.info("CIP-68 FT minted. TxHash: {}, PolicyId: {}, FT AssetName: {}",
                result.getValue(), policyId, ftAssetNameHex);

        return new MintResult(policyId, ftAssetNameHex, result.getValue());
    }

    public void waitForTransaction(Result<String> result) {
        try {
            if (result.isSuccessful()) {
                int count = 0;
                while (count < 60) {
                    Result<TransactionContent> txnResult =
                            backendService.getTransactionService().getTransaction(result.getValue());
                    if (txnResult.isSuccessful()) {
                        break;
                    }
                    count++;
                    Thread.sleep(2000);
                }
            }
        } catch (Exception e) {
            log.warn("Error waiting for transaction", e);
        }
    }

    private static void topUpFund(String address, long adaAmount) {
        try {
            String url = DEVKIT_ADMIN_BASE_URL + "local-cluster/api/addresses/topup";
            URL obj = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) obj.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            String jsonInputString = String.format("{\"address\": \"%s\", \"adaAmount\": %d}", address, adaAmount);
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                log.info("Funds topped up successfully for {}", address);
            } else {
                log.warn("Failed to top up funds. Response code: {}", responseCode);
            }
        } catch (Exception e) {
            log.warn("Could not topup address: {}", e.getMessage());
        }
    }
}
