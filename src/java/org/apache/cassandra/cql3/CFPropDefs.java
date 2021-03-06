/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.compaction.AbstractCompactionStrategy;
import org.apache.cassandra.io.compress.CompressionParameters;

public class CFPropDefs extends PropertyDefinitions
{
    private static final Logger logger = LoggerFactory.getLogger(CFPropDefs.class);

    public static final String KW_COMMENT = "comment";
    public static final String KW_READREPAIRCHANCE = "read_repair_chance";
    public static final String KW_DCLOCALREADREPAIRCHANCE = "dclocal_read_repair_chance";
    public static final String KW_GCGRACESECONDS = "gc_grace_seconds";
    public static final String KW_MINCOMPACTIONTHRESHOLD = "min_threshold";
    public static final String KW_MAXCOMPACTIONTHRESHOLD = "max_threshold";
    public static final String KW_REPLICATEONWRITE = "replicate_on_write";
    public static final String KW_CACHING = "caching";
    public static final String KW_BF_FP_CHANCE = "bloom_filter_fp_chance";
    public static final String KW_DEFAULT_R_CONSISTENCY = "default_read_consistency";
    public static final String KW_DEFAULT_W_CONSISTENCY = "default_write_consistency";

    public static final String KW_COMPACTION = "compaction";
    public static final String KW_COMPRESSION = "compression";

    public static final String COMPACTION_STRATEGY_CLASS_KEY = "class";

    public static final Set<String> keywords = new HashSet<String>();
    public static final Set<String> obsoleteKeywords = new HashSet<String>();

    static
    {
        keywords.add(KW_COMMENT);
        keywords.add(KW_READREPAIRCHANCE);
        keywords.add(KW_DCLOCALREADREPAIRCHANCE);
        keywords.add(KW_GCGRACESECONDS);
        keywords.add(KW_REPLICATEONWRITE);
        keywords.add(KW_CACHING);
        keywords.add(KW_BF_FP_CHANCE);
        keywords.add(KW_COMPACTION);
        keywords.add(KW_COMPRESSION);
        keywords.add(KW_DEFAULT_W_CONSISTENCY);
        keywords.add(KW_DEFAULT_R_CONSISTENCY);

        obsoleteKeywords.add("compaction_strategy_class");
        obsoleteKeywords.add("compaction_strategy_options");
        obsoleteKeywords.add("min_compaction_threshold");
        obsoleteKeywords.add("max_compaction_threshold");
        obsoleteKeywords.add("compaction_parameters");
        obsoleteKeywords.add("compression_parameters");
    }

    private Class<? extends AbstractCompactionStrategy> compactionStrategyClass = null;

    public void validate() throws ConfigurationException, SyntaxException
    {
        validate(keywords, obsoleteKeywords);

        Map<String, String> compactionOptions = getCompactionOptions();
        if (!compactionOptions.isEmpty())
        {
            String strategy = compactionOptions.get(COMPACTION_STRATEGY_CLASS_KEY);
            if (strategy == null)
                throw new ConfigurationException("Missing sub-option '" + COMPACTION_STRATEGY_CLASS_KEY + "' for the '" + KW_COMPACTION + "' option.");

            compactionStrategyClass = CFMetaData.createCompactionStrategy(strategy);
            compactionOptions.remove(COMPACTION_STRATEGY_CLASS_KEY);
        }
    }

    public Map<String, String> getCompactionOptions() throws SyntaxException
    {
        Map<String, String> compactionOptions = getMap(KW_COMPACTION);
        if (compactionOptions == null)
            return Collections.<String, String>emptyMap();
        return compactionOptions;
    }

    public Map<String, String> getCompressionOptions() throws SyntaxException
    {
        Map<String, String> compressionOptions = getMap(KW_COMPRESSION);
        if (compressionOptions == null)
        {
            return new HashMap<String, String>()
            {{
                 if (CFMetaData.DEFAULT_COMPRESSOR != null)
                     put(CompressionParameters.SSTABLE_COMPRESSION, CFMetaData.DEFAULT_COMPRESSOR);
            }};
        }
        return compressionOptions;
    }

    public void applyToCFMetadata(CFMetaData cfm) throws ConfigurationException, SyntaxException
    {
        if (hasProperty(KW_COMMENT))
            cfm.comment(getString(KW_COMMENT, ""));

        cfm.readRepairChance(getDouble(KW_READREPAIRCHANCE, cfm.getReadRepairChance()));
        cfm.dcLocalReadRepairChance(getDouble(KW_DCLOCALREADREPAIRCHANCE, cfm.getDcLocalReadRepair()));
        cfm.gcGraceSeconds(getInt(KW_GCGRACESECONDS, cfm.getGcGraceSeconds()));
        cfm.replicateOnWrite(getBoolean(KW_REPLICATEONWRITE, cfm.getReplicateOnWrite()));
        cfm.minCompactionThreshold(toInt(KW_MINCOMPACTIONTHRESHOLD, getCompactionOptions().get(KW_MINCOMPACTIONTHRESHOLD), cfm.getMinCompactionThreshold()));
        cfm.maxCompactionThreshold(toInt(KW_MAXCOMPACTIONTHRESHOLD, getCompactionOptions().get(KW_MAXCOMPACTIONTHRESHOLD), cfm.getMaxCompactionThreshold()));
        cfm.caching(CFMetaData.Caching.fromString(getString(KW_CACHING, cfm.getCaching().toString())));
        cfm.bloomFilterFpChance(getDouble(KW_BF_FP_CHANCE, cfm.getBloomFilterFpChance()));

        if (compactionStrategyClass != null)
        {
            cfm.compactionStrategyClass(compactionStrategyClass);
            cfm.compactionStrategyOptions(new HashMap<String, String>(getCompactionOptions()));
        }

        if (!getCompressionOptions().isEmpty())
            cfm.compressionParameters(CompressionParameters.create(getCompressionOptions()));

        try
        {
            ConsistencyLevel readCL = getConsistencyLevel(KW_DEFAULT_R_CONSISTENCY);
            if (readCL != null)
            {
                readCL.validateForRead(cfm.ksName);
                cfm.defaultReadCL(readCL);
            }
            ConsistencyLevel writeCL = getConsistencyLevel(KW_DEFAULT_W_CONSISTENCY);
            if (writeCL != null)
            {
                writeCL.validateForWrite(cfm.ksName);
                cfm.defaultWriteCL(writeCL);
            }
        }
        catch (InvalidRequestException e)
        {
            throw new ConfigurationException(e.getMessage(), e.getCause());
        }
    }

    public ConsistencyLevel getConsistencyLevel(String key) throws ConfigurationException, SyntaxException
    {
        String value = getSimple(key);
        if (value == null)
            return null;

        try
        {
            return Enum.valueOf(ConsistencyLevel.class, value);
        }
        catch (IllegalArgumentException e)
        {
            throw new ConfigurationException(String.format("Invalid consistency level value: %s", value));
        }
    }

    @Override
    public String toString()
    {
        return String.format("CFPropDefs(%s)", properties.toString());
    }
}
