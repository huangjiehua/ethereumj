package org.ethereum.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigRenderOptions;
import org.ethereum.config.blockchain.OlympicConfig;
import org.ethereum.config.net.MainNetConfig;
import org.ethereum.config.net.MordenNetConfig;
import org.ethereum.config.net.TestNetConfig;
import org.ethereum.core.Genesis;
import org.ethereum.core.genesis.GenesisLoader;
import org.ethereum.crypto.ECKey;
import org.ethereum.net.p2p.P2pHandler;
import org.ethereum.net.rlpx.MessageCodec;
import org.ethereum.net.rlpx.Node;
import org.ethereum.util.BuildInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.io.*;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.*;

import static org.ethereum.crypto.SHA3Helper.sha3;

/**
 * Utility class to retrieve property values from the ethereumj.conf files
 *
 * The properties are taken from different sources and merged in the following order
 * (the config option from the next source overrides option from previous):
 * - resource ethereumj.conf : normally used as a reference config with default values
 *          and shouldn't be changed
 * - system property : each config entry might be altered via -D VM option
 * - [user dir]/config/ethereumj.conf
 * - config specified with the -Dethereumj.conf.file=[file.conf] VM option
 * - CLI options
 *
 * @author Roman Mandeleil
 * @since 22.05.2014
 */
public class SystemProperties {
    private static Logger logger = LoggerFactory.getLogger("general");

    public final static String PROPERTY_DB_DIR = "database.dir";
    public final static String PROPERTY_LISTEN_PORT = "peer.listen.port";
    public final static String PROPERTY_PEER_ACTIVE = "peer.active";
    public final static String PROPERTY_DB_RESET = "database.reset";

    /* Testing */
    private final static Boolean DEFAULT_VMTEST_LOAD_LOCAL = false;
    private final static String DEFAULT_BLOCKS_LOADER = "";

    public final static SystemProperties CONFIG = new SystemProperties();

    /**
     * Marks config accessor methods which need to be called (for value validation)
     * upon config creation or modification
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    private @interface ValidateMe {};


    private Config config;

    // mutable options for tests
    private String databaseDir = null;
    private Boolean databaseReset = null;
    private String projectVersion = null;
    private String projectVersionModifier = null;

    private String genesisInfo = null;

    private String bindIp = null;
    private String externalIp = null;

    private Boolean syncEnabled = null;
    private Boolean discoveryEnabled = null;

    private BlockchainNetConfig blockchainConfig;
    private Genesis genesis;

    public SystemProperties() {
        this(ConfigFactory.empty());
    }

    public SystemProperties(File configFile) {
        this(ConfigFactory.parseFile(configFile));
    }

    public SystemProperties(String configResource) {
        this(ConfigFactory.parseResources(configResource));
    }

    public SystemProperties(Config apiConfig) {
        try {
            Config javaSystemProperties = ConfigFactory.load("no-such-resource-only-system-props");
            Config referenceConfig = ConfigFactory.parseResources("ethereumj.conf");
            logger.info("Config (" + (referenceConfig.entrySet().size() > 0 ? " yes " : " no  ") + "): default properties from resource 'ethereumj.conf'");
            String res = System.getProperty("ethereumj.conf.res");
            Config cmdLineConfigRes = res != null ? ConfigFactory.parseResources(res) : ConfigFactory.empty();
            logger.info("Config (" + (cmdLineConfigRes.entrySet().size() > 0 ? " yes " : " no  ") + "): user properties from -Dethereumj.conf.res resource '" + res + "'");
            Config userConfig = ConfigFactory.parseResources("user.conf");
            logger.info("Config (" + (userConfig.entrySet().size() > 0 ? " yes " : " no  ") + "): user properties from resource 'user.conf'");
            File userDirFile = new File(System.getProperty("user.dir"), "/config/ethereumj.conf");
            Config userDirConfig = ConfigFactory.parseFile(userDirFile);
            logger.info("Config (" + (userDirConfig.entrySet().size() > 0 ? " yes " : " no  ") + "): user properties from file '" + userDirFile + "'");
            Config testConfig = ConfigFactory.parseResources("test-ethereumj.conf");
            logger.info("Config (" + (testConfig.entrySet().size() > 0 ? " yes " : " no  ") + "): test properties from resource 'test-ethereumj.conf'");
            Config testUserConfig = ConfigFactory.parseResources("test-user.conf");
            logger.info("Config (" + (testUserConfig.entrySet().size() > 0 ? " yes " : " no  ") + "): test properties from resource 'test-user.conf'");
            String file = System.getProperty("ethereumj.conf.file");
            Config cmdLineConfigFile = file != null ? ConfigFactory.parseFile(new File(file)) : ConfigFactory.empty();
            logger.info("Config (" + (cmdLineConfigFile.entrySet().size() > 0 ? " yes " : " no  ") + "): user properties from -Dethereumj.conf.file file '" + file + "'");
            logger.info("Config (" + (apiConfig.entrySet().size() > 0 ? " yes " : " no  ") + "): config passed via constructor");
            config = apiConfig
                    .withFallback(cmdLineConfigFile)
                    .withFallback(testUserConfig)
                    .withFallback(testConfig)
                    .withFallback(userDirConfig)
                    .withFallback(userConfig)
                    .withFallback(cmdLineConfigRes)
                    .withFallback(referenceConfig);

            logger.debug("Config trace: " + config.root().render(ConfigRenderOptions.defaults().
                    setComments(false).setJson(false)));

            config = javaSystemProperties.withFallback(config);
            validateConfig();

            Properties props = new Properties();
            InputStream is = getClass().getResourceAsStream("/version.properties");
            props.load(is);
            this.projectVersion = props.getProperty("versionNumber");
            this.projectVersion = this.projectVersion.replaceAll("'", "");

            if (this.projectVersion == null) this.projectVersion = "-.-.-";

            this.projectVersionModifier = "master".equals(BuildInfo.buildBranch) ? "RELEASE" : "SNAPSHOT";

        } catch (Exception e) {
            logger.error("Can't read config.", e);
            throw new RuntimeException(e);
        }
    }

    public Config getConfig() {
        return config;
    }

    /**
     * Puts a new config atop of existing stack making the options
     * in the supplied config overriding existing options
     * Once put this config can't be removed
     *
     * @param overrideOptions - atop config
     */
    public void overrideParams(Config overrideOptions) {
        config = overrideOptions.withFallback(config);
        validateConfig();
    }

    /**
     * Puts a new config atop of existing stack making the options
     * in the supplied config overriding existing options
     * Once put this config can't be removed
     *
     * @param keyValuePairs [name] [value] [name] [value] ...
     */
    public void overrideParams(String ... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) throw new RuntimeException("Odd argument number");
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        overrideParams(map);
    }

    /**
     * Puts a new config atop of existing stack making the options
     * in the supplied config overriding existing options
     * Once put this config can't be removed
     *
     * @param cliOptions -  command line options to take presidency
     */
    public void overrideParams(Map<String, String> cliOptions) {
        Config cliConf = ConfigFactory.parseMap(cliOptions);
        overrideParams(cliConf);
    }

    private void validateConfig() {
        for (Method method : getClass().getMethods()) {
            try {
                if (method.isAnnotationPresent(ValidateMe.class)) {
                    method.invoke(this);
                }
            } catch (Exception e) {
                throw new RuntimeException("Error validating config method: " + method, e);
            }
        }
    }

    public <T> T getProperty(String propName, T defaultValue) {
        if (!config.hasPath(propName)) return defaultValue;
        String string = config.getString(propName);
        if (string.trim().isEmpty()) return defaultValue;
        return (T) config.getAnyRef(propName);
    }

    public BlockchainNetConfig getBlockchainConfig() {
        if (blockchainConfig == null) {
            if (config.hasPath("blockchain.config.name") && config.hasPath("blockchain.config.class")) {
                throw new RuntimeException("Only one of two options should be defined: 'blockchain.config.name' and 'blockchain.config.class'");
            }
            if (config.hasPath("blockchain.config.name")) {
                switch(config.getString("blockchain.config.name")) {
                    case "main":
                        blockchainConfig = new MainNetConfig();
                        break;
                    case "olympic":
                        blockchainConfig = new OlympicConfig();
                        break;
                    case "morden":
                        blockchainConfig = new MordenNetConfig();
                        break;
                    case "testnet":
                        blockchainConfig = new TestNetConfig();
                        break;
                    default:
                        throw new RuntimeException("Unknown value for 'blockchain.config.name': '" + config.getString("blockchain.config.name") + "'");
                }
            } else {
                String className = config.getString("blockchain.config.class");
                try {
                    Class<? extends BlockchainNetConfig> aClass = (Class<? extends BlockchainNetConfig>) Class.forName(className);
                    blockchainConfig = aClass.newInstance();
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("The class specified via blockchain.config.class '" + className + "' not found" , e);
                } catch (ClassCastException e) {
                    throw new RuntimeException("The class specified via blockchain.config.class '" + className + "' is not instance of org.ethereum.config.BlockchainForkConfig" , e);
                } catch (InstantiationException|IllegalAccessException e) {
                    throw new RuntimeException("The class specified via blockchain.config.class '" + className + "' couldn't be instantiated (check for default constructor and its accessibility)" , e);
                }
            }
        }
        return blockchainConfig;
    }

    public void setBlockchainConfig(BlockchainNetConfig config) {
        blockchainConfig = config;
    }

    @ValidateMe
    public boolean peerDiscovery() {
        return discoveryEnabled == null ? config.getBoolean("peer.discovery.enabled") : discoveryEnabled;
    }

    public void setDiscoveryEnabled(Boolean discoveryEnabled) {
        this.discoveryEnabled = discoveryEnabled;
    }

    @ValidateMe
    public boolean peerDiscoveryPersist() {
        return config.getBoolean("peer.discovery.persist");
    }

    @ValidateMe
    public int peerDiscoveryWorkers() {
        return config.getInt("peer.discovery.workers");
    }

    @ValidateMe
    public int peerDiscoveryTouchPeriod() {
        return config.getInt("peer.discovery.touchPeriod");
    }

    @ValidateMe
    public int peerDiscoveryTouchMaxNodes() {
        return config.getInt("peer.discovery.touchMaxNodes");
    }

    @ValidateMe
    public int peerConnectionTimeout() {
        return config.getInt("peer.connection.timeout") * 1000;
    }

    @ValidateMe
    public int defaultP2PVersion() {
        return config.hasPath("peer.p2p.version") ? config.getInt("peer.p2p.version") : P2pHandler.VERSION;
    }

    @ValidateMe
    public int rlpxMaxFrameSize() {
        return config.hasPath("peer.p2p.framing.maxSize") ? config.getInt("peer.p2p.framing.maxSize") : MessageCodec.NO_FRAMING;
    }


    @ValidateMe
    public int transactionApproveTimeout() {
        return config.getInt("transaction.approve.timeout") * 1000;
    }

    @ValidateMe
    public List<String> peerDiscoveryIPList() {
        return config.getStringList("peer.discovery.ip.list");
    }

    @ValidateMe
    public boolean databaseReset() {
        return databaseReset == null ? config.getBoolean("database.reset") : databaseReset;
    }

    public void setDatabaseReset(Boolean reset) {
        databaseReset = reset;
    }

    @ValidateMe
    public List<Node> peerActive() {
        if (!config.hasPath("peer.active")) {
            return Collections.EMPTY_LIST;
        }
        List<Node> ret = new ArrayList<>();
        List<? extends ConfigObject> list = config.getObjectList("peer.active");
        for (ConfigObject configObject : list) {
            Node n;
            if (configObject.get("url") != null) {
                String url = configObject.toConfig().getString("url");
                n = new Node(url.startsWith("enode://") ? url : "enode://" + url);
            } else if (configObject.get("ip") != null) {
                String ip = configObject.toConfig().getString("ip");
                int port = configObject.toConfig().getInt("port");
                byte[] nodeId;
                if (configObject.toConfig().hasPath("nodeId")) {
                    nodeId = Hex.decode(configObject.toConfig().getString("nodeId").trim());
                    if (nodeId.length != 64) {
                        throw new RuntimeException("Invalid config nodeId '" + nodeId + "' at " + configObject);
                    }
                } else {
                    if (configObject.toConfig().hasPath("nodeName")) {
                        String nodeName = configObject.toConfig().getString("nodeName").trim();
                        // FIXME should be sha3-512 here ?
                        nodeId = ECKey.fromPrivate(sha3(nodeName.getBytes())).getNodeId();
                    } else {
                        throw new RuntimeException("Either nodeId or nodeName should be specified: " + configObject);
                    }
                }
                n = new Node(nodeId, ip, port);
            } else {
                throw new RuntimeException("Unexpected element within 'peer.active' config list: " + configObject);
            }
            ret.add(n);
        }
        return ret;
    }

    @ValidateMe
    public NodeFilter peerTrusted() {
        List<? extends ConfigObject> list = config.getObjectList("peer.trusted");
        NodeFilter ret = new NodeFilter();

        for (ConfigObject configObject : list) {
            byte[] nodeId = null;
            String ipMask = null;
            if (configObject.get("nodeId") != null) {
                nodeId = Hex.decode(configObject.toConfig().getString("nodeId").trim());
            }
            if (configObject.get("ip") != null) {
                ipMask = configObject.toConfig().getString("ip").trim();
            }
            ret.add(nodeId, ipMask);
        }
        return ret;
    }


    public String samplesDir() {
        return config.getString("samples.dir");
    }

    @ValidateMe
    public String coinbaseSecret() {
        return config.getString("coinbase.secret");
    }

    @ValidateMe
    public Integer peerChannelReadTimeout() {
        return config.getInt("peer.channel.read.timeout");
    }

    @ValidateMe
    public Integer traceStartBlock() {
        return config.getInt("trace.startblock");
    }

    @ValidateMe
    public boolean recordBlocks() {
        return config.getBoolean("record.blocks");
    }

    @ValidateMe
    public boolean dumpFull() {
        return config.getBoolean("dump.full");
    }

    @ValidateMe
    public String dumpDir() {
        return config.getString("dump.dir");
    }

    @ValidateMe
    public String dumpStyle() {
        return config.getString("dump.style");
    }

    @ValidateMe
    public int dumpBlock() {
        return config.getInt("dump.block");
    }

    @ValidateMe
    public String databaseDir() {
        return databaseDir == null ? config.getString("database.dir") : databaseDir;
    }

    public void setDataBaseDir(String dataBaseDir) {
        this.databaseDir = dataBaseDir;
    }

    @ValidateMe
    public boolean dumpCleanOnRestart() {
        return config.getBoolean("dump.clean.on.restart");
    }

    @ValidateMe
    public boolean playVM() {
        return config.getBoolean("play.vm");
    }

    @ValidateMe
    public boolean blockChainOnly() {
        return config.getBoolean("blockchain.only");
    }

    @ValidateMe
    public int maxHashesAsk() {
        return config.getInt("sync.max.hashes.ask");
    }

    @ValidateMe
    public int maxBlocksAsk() {
        return config.getInt("sync.max.blocks.ask");
    }

    @ValidateMe
    public int syncPeerCount() {
        return config.getInt("sync.peer.count");
    }

    public Integer syncVersion() {
        if (!config.hasPath("sync.version")) {
            return null;
        }
        return config.getInt("sync.version");
    }

    @ValidateMe
    public boolean exitOnBlockConflict() {
        return config.getBoolean("sync.exitOnBlockConflict");
    }

    @ValidateMe
    public String projectVersion() {
        return projectVersion;
    }

    @ValidateMe
    public String projectVersionModifier() {
        return projectVersionModifier;
    }

    @ValidateMe
    public String helloPhrase() {
        return config.getString("hello.phrase");
    }

    @ValidateMe
    public String rootHashStart() {
        return config.hasPath("root.hash.start") ? config.getString("root.hash.start") : null;
    }

    @ValidateMe
    public List<String> peerCapabilities() {
        return config.getStringList("peer.capabilities");
    }

    @ValidateMe
    public boolean vmTrace() {
        return config.getBoolean("vm.structured.trace");
    }

    @ValidateMe
    public boolean vmTraceCompressed() {
        return config.getBoolean("vm.structured.compressed");
    }

    @ValidateMe
    public int vmTraceInitStorageLimit() {
        return config.getInt("vm.structured.initStorageLimit");
    }

    @ValidateMe
    public int detailsInMemoryStorageLimit() {
        return config.getInt("details.inmemory.storage.limit");
    }

    @ValidateMe
    public double cacheFlushMemory() {
        return config.getDouble("cache.flush.memory");
    }

    @ValidateMe
    public int cacheFlushBlocks() {
        return config.getInt("cache.flush.blocks");
    }

    @ValidateMe
    public String vmTraceDir() {
        return config.getString("vm.structured.dir");
    }

    @ValidateMe
    public String privateKey() {
        if (config.hasPath("peer.privateKey")) {
            String key = config.getString("peer.privateKey");
            if (key.length() != 64) {
                throw new RuntimeException("The peer.privateKey needs to be Hex encoded and 32 byte length");
            }
            return key;
        } else {
            return getGeneratedNodePrivateKey();
        }
    }

    private String getGeneratedNodePrivateKey() {
        try {
            File file = new File(databaseDir(), "nodeId.properties");
            Properties props = new Properties();
            if (file.canRead()) {
                props.load(new FileReader(file));
            } else {
                ECKey key = new ECKey();
                props.setProperty("nodeIdPrivateKey", Hex.toHexString(key.getPrivKeyBytes()));
                props.setProperty("nodeId", Hex.toHexString(key.getNodeId()));
                file.getParentFile().mkdirs();
                props.store(new FileWriter(file), "Generated NodeID. To use your own nodeId please refer to 'peer.privateKey' config option.");
                logger.info("New nodeID generated: " + props.getProperty("nodeId"));
                logger.info("Generated nodeID and its private key stored in " + file);
            }
            return props.getProperty("nodeIdPrivateKey");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @ValidateMe
    public ECKey getMyKey() {
        return ECKey.fromPrivate(Hex.decode(privateKey()));
    }

    /**
     *  Home NodeID calculated from 'peer.privateKey' property
     */
    @ValidateMe
    public byte[] nodeId() {
        return getMyKey().getNodeId();
    }

    @ValidateMe
    public int networkId() {
        return config.getInt("peer.networkId");
    }

    @ValidateMe
    public int maxActivePeers() {
        return config.getInt("peer.maxActivePeers");
    }

    @ValidateMe
    public boolean eip8() {
        return config.getBoolean("peer.p2p.eip8");
    }

    @ValidateMe
    public int listenPort() {
        return config.getInt("peer.listen.port");
    }


    /**
     * This can be a blocking call with long timeout (thus no ValidateMe)
     */
    public String bindIp() {
        if (!config.hasPath("peer.discovery.bind.ip") || config.getString("peer.discovery.bind.ip").trim().isEmpty()) {
            if (bindIp == null) {
                logger.info("Bind address wasn't set, Punching to identify it...");
                try {
                    Socket s = new Socket("www.google.com", 80);
                    bindIp = s.getLocalAddress().getHostAddress();
                    logger.info("UDP local bound to: {}", bindIp);
                } catch (IOException e) {
                    logger.warn("Can't get bind IP. Fall back to 0.0.0.0: " + e);
                    bindIp = "0.0.0.0";
                }
            }
            return bindIp;
        } else {
            return config.getString("peer.discovery.bind.ip").trim();
        }
    }

    /**
     * This can be a blocking call with long timeout (thus no ValidateMe)
     */
    public String externalIp() {
        if (!config.hasPath("peer.discovery.external.ip") || config.getString("peer.discovery.external.ip").trim().isEmpty()) {
            if (externalIp == null) {
                logger.info("External IP wasn't set, using checkip.amazonaws.com to identify it...");
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            new URL("http://checkip.amazonaws.com").openStream()));
                    externalIp = in.readLine();
                    if (externalIp == null || externalIp.trim().isEmpty()) {
                        throw new IOException("Invalid address: '" + externalIp + "'");
                    }
                    try {
                        InetAddress.getByName(externalIp);
                    } catch (Exception e) {
                        throw new IOException("Invalid address: '" + externalIp + "'");
                    }
                    logger.info("External address identified: {}", externalIp);
                } catch (IOException e) {
                    externalIp = bindIp();
                    logger.warn("Can't get external IP. Fall back to peer.bind.ip: " + externalIp + " :" + e);
                }
            }
            return externalIp;

        } else {
            return config.getString("peer.discovery.external.ip").trim();
        }
    }

    @ValidateMe
    public String getKeyValueDataSource() {
        return config.getString("keyvalue.datasource");
    }

    @ValidateMe
    public boolean isRedisEnabled() {
        return config.getBoolean("redis.enabled");
    }

    @ValidateMe
    public boolean isSyncEnabled() {
        return this.syncEnabled == null ? config.getBoolean("sync.enabled") : syncEnabled;
    }

    public void setSyncEnabled(Boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    @ValidateMe
    public boolean isPublicHomeNode() { return config.getBoolean("peer.discovery.public.home.node");}

    @ValidateMe
    public String genesisInfo() {

        if (genesisInfo == null)
            return config.getString("genesis");
        else
            return genesisInfo;
    }

    @ValidateMe
    public int txOutdatedThreshold() {
        return config.getInt("transaction.outdated.threshold");
    }

    public void setGenesisInfo(String genesisInfo){
        this.genesisInfo = genesisInfo;
    }

    @ValidateMe
    public boolean minerStart() {
        return config.getBoolean("mine.start");
    }

    @ValidateMe
    public byte[] getMinerCoinbase() {
        String sc = config.getString("mine.coinbase");
        byte[] c = Hex.decode(sc);
        if (c.length != 20) throw new RuntimeException("mine.coinbase has invalid value: '" + sc + "'");
        return c;
    }

    @ValidateMe
    public byte[] getMineExtraData() {
        byte[] bytes;
        if (config.hasPath("mine.extraDataHex")) {
            bytes = Hex.decode(config.getString("mine.extraDataHex"));
        } else {
            bytes = config.getString("mine.extraData").getBytes();
        }
        if (bytes.length > 32) throw new RuntimeException("mine.extraData exceed 32 bytes length: " + bytes.length);
        return bytes;
    }

    @ValidateMe
    public BigInteger getMineMinGasPrice() {
        return new BigInteger(config.getString("mine.minGasPrice"));
    }

    @ValidateMe
    public long getMineMinBlockTimeoutMsec() {
        return config.getLong("mine.minBlockTimeoutMsec");
    }

    @ValidateMe
    public int getMineCpuThreads() {
        return config.getInt("mine.cpuMineThreads");
    }

    @ValidateMe
    public boolean isMineFullDataset() {
        return config.getBoolean("mine.fullDataSet");
    }

    public Genesis getGenesis() {
        if (genesis == null) {
            genesis = GenesisLoader.loadGenesis(this);
        }
        return genesis;
    }

    public String dump() {
        return config.root().render(ConfigRenderOptions.defaults().setComments(false));
    }

    /*
     *
     * Testing
     *
     */
    public boolean vmTestLoadLocal() {
        return config.hasPath("GitHubTests.VMTest.loadLocal") ?
                config.getBoolean("GitHubTests.VMTest.loadLocal") : DEFAULT_VMTEST_LOAD_LOCAL;
    }

    public String blocksLoader() {
        return config.hasPath("blocks.loader") ?
                config.getString("blocks.loader") : DEFAULT_BLOCKS_LOADER;
    }
}
