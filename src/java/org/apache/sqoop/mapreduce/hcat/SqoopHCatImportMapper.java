/**
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

package org.apache.sqoop.mapreduce.hcat;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hcatalog.common.HCatConstants;
import org.apache.hcatalog.common.HCatUtil;
import org.apache.hcatalog.data.DefaultHCatRecord;
import org.apache.hcatalog.data.HCatRecord;
import org.apache.hcatalog.data.schema.HCatFieldSchema;
import org.apache.hcatalog.data.schema.HCatSchema;
import org.apache.hcatalog.mapreduce.InputJobInfo;
import org.apache.sqoop.lib.SqoopRecord;
import org.apache.sqoop.mapreduce.ImportJobBase;
import org.apache.sqoop.mapreduce.SqoopMapper;

import com.cloudera.sqoop.lib.BlobRef;
import com.cloudera.sqoop.lib.ClobRef;
import com.cloudera.sqoop.lib.LargeObjectLoader;

/**
 * A mapper for HCatalog import.
 */
public class SqoopHCatImportMapper extends
    SqoopMapper<WritableComparable, SqoopRecord, WritableComparable, HCatRecord> {
  public static final Log LOG = LogFactory
    .getLog(SqoopHCatImportMapper.class.getName());
  public static final String DEBUG_HCAT_IMPORT_MAPPER_PROP =
    "sqoop.debug.import.mapper";
  private static boolean debugHCatImportMapper = false;

  private InputJobInfo jobInfo;
  private HCatSchema hCatFullTableSchema;
  private int fieldCount;
  private boolean bigDecimalFormatString;
  private LargeObjectLoader lobLoader;
  private HCatSchema partitionSchema = null;
  private HCatSchema dataColsSchema = null;
  @Override
  protected void setup(Context context)
    throws IOException, InterruptedException {
    Configuration conf = context.getConfiguration();
    String inputJobInfoStr = conf.get(HCatConstants.HCAT_KEY_JOB_INFO);
    jobInfo =
      (InputJobInfo) HCatUtil.deserialize(inputJobInfoStr);
    dataColsSchema = jobInfo.getTableInfo().getDataColumns();
    partitionSchema =
      jobInfo.getTableInfo().getPartitionColumns();
    hCatFullTableSchema = new HCatSchema(dataColsSchema.getFields());
    for (HCatFieldSchema hfs : partitionSchema.getFields()) {
      hCatFullTableSchema.append(hfs);
    }
    fieldCount = hCatFullTableSchema.size();
    lobLoader = new LargeObjectLoader(conf,
      new Path(jobInfo.getTableInfo().getTableLocation()));
    bigDecimalFormatString = conf.getBoolean(
      ImportJobBase.PROPERTY_BIGDECIMAL_FORMAT,
      ImportJobBase.PROPERTY_BIGDECIMAL_FORMAT_DEFAULT);
    debugHCatImportMapper = conf.getBoolean(
      DEBUG_HCAT_IMPORT_MAPPER_PROP, false);

  }

  @Override
  public void map(WritableComparable key, SqoopRecord value,
    Context context)
    throws IOException, InterruptedException {

    try {
      // Loading of LOBs was delayed until we have a Context.
      value.loadLargeObjects(lobLoader);
    } catch (SQLException sqlE) {
      throw new IOException(sqlE);
    }

    context.write(key, convertToHCatRecord(value));
  }

  @Override
  protected void cleanup(Context context) throws IOException {
    if (null != lobLoader) {
      lobLoader.close();
    }
  }

  private HCatRecord convertToHCatRecord(SqoopRecord sqr)
    throws IOException {
    Map<String, Object> fieldMap = sqr.getFieldMap();
    HCatRecord result = new DefaultHCatRecord(fieldMap.keySet().size());

    for (Map.Entry<String, Object> entry : fieldMap.entrySet()) {
      String key = entry.getKey();
      Object val = entry.getValue();
      HCatFieldSchema hfs = hCatFullTableSchema.get(key.toLowerCase());
      if (debugHCatImportMapper) {
        LOG.debug("SqoopRecordVal: field = " + key + " Val " + val
          + " of type " + (val == null ? null : val.getClass().getName())
          + ", hcattype " + hfs.getTypeString());
      }
      Object hCatVal = toHCat(val, hfs.getType(), hfs.getTypeString());
      // This needs to be checked.
      // if (hCatVal == null
      // && partitionSchema.getFieldNames().contains(key.toLowerCase())) {
      // throw new IOException("Dynamic partition keys cannot be null."
      // + "  Please make sure that the column " + key
      // + " is declared as not null in the database");
      // }
      result.set(key.toLowerCase(), hCatFullTableSchema, hCatVal);
    }

    return result;
  }

  private Object toHCat(Object val, HCatFieldSchema.Type hfsType,
    String hCatTypeString) {

    if (val == null) {
      return null;
    }

    Object retVal = null;

    if (val instanceof Number) {
      retVal = convertFromNumberType(val, hfsType);
    } else if (val instanceof Boolean) {
      retVal = convertFromBooleanType(val, hfsType);
    } else if (val instanceof String) {
      if (hfsType == HCatFieldSchema.Type.STRING) {
        retVal = val;
      }
    } else if (val instanceof java.util.Date) {
      retVal = convertFromDateTimeTypes(val, hfsType);
    } else if (val instanceof BytesWritable) {
      if (hfsType == HCatFieldSchema.Type.BINARY) {
        BytesWritable bw = (BytesWritable) val;
        retVal = bw.getBytes();
      }
    } else if (val instanceof BlobRef) {
      if (hfsType == HCatFieldSchema.Type.BINARY) {
        BlobRef br = (BlobRef) val;
        byte[] bytes = br.isExternal() ? br.toString().getBytes()
          : br.getData();
        retVal = bytes;
      }
    } else if (val instanceof ClobRef) {
      if (hfsType == HCatFieldSchema.Type.STRING) {
        ClobRef cr = (ClobRef) val;
        String s = cr.isExternal() ? cr.toString() : cr.getData();
        retVal = s;
      }
    } else {
      throw new UnsupportedOperationException("Objects of type "
        + val.getClass().getName() + " are not suported");
    }
    if (retVal == null) {
      throw new UnsupportedOperationException("Objects of type "
        + val.getClass().getName() + " can not be mapped to HCatalog type "
        + hCatTypeString);
    }
    return retVal;
  }

  private Object convertFromDateTimeTypes(Object val,
    HCatFieldSchema.Type hfsType) {
    if (val instanceof java.sql.Date) {
      if (hfsType == HCatFieldSchema.Type.BIGINT) {
        return ((Date) val).getTime();
      } else if (hfsType == HCatFieldSchema.Type.STRING) {
        return val.toString();
      }
    } else if (val instanceof java.sql.Time) {
      if (hfsType == HCatFieldSchema.Type.BIGINT) {
        return ((Time) val).getTime();
      } else if (hfsType == HCatFieldSchema.Type.STRING) {
        return val.toString();
      }
    } else if (val instanceof java.sql.Timestamp) {
      if (hfsType == HCatFieldSchema.Type.BIGINT) {
        return ((Timestamp) val).getTime();
      } else if (hfsType == HCatFieldSchema.Type.STRING) {
        return val.toString();
      }
    }
    return null;
  }

  private Object convertFromBooleanType(Object val,
    HCatFieldSchema.Type hfsType) {
    Boolean b = (Boolean) val;
    if (hfsType == HCatFieldSchema.Type.BOOLEAN) {
      return b;
    } else if (hfsType == HCatFieldSchema.Type.TINYINT) {
      return (byte) (b ? 1 : 0);
    } else if (hfsType == HCatFieldSchema.Type.SMALLINT) {
      return (short) (b ? 1 : 0);
    } else if (hfsType == HCatFieldSchema.Type.INT) {
      return (int) (b ? 1 : 0);
    } else if (hfsType == HCatFieldSchema.Type.BIGINT) {
      return (long) (b ? 1 : 0);
    } else if (hfsType == HCatFieldSchema.Type.FLOAT) {
      return (float) (b ? 1 : 0);
    } else if (hfsType == HCatFieldSchema.Type.DOUBLE) {
      return (double) (b ? 1 : 0);
    }
    return null;
  }

  private Object convertFromNumberType(Object val,
    HCatFieldSchema.Type hfsType) {
    if (!(val instanceof Number)) {
      return null;
    }
    if (val instanceof BigDecimal && hfsType == HCatFieldSchema.Type.STRING) {
      BigDecimal bd = (BigDecimal) val;
      if (bigDecimalFormatString) {
        return bd.toPlainString();
      } else {
        return bd.toString();
      }
    }
    Number n = (Number) val;
    if (hfsType == HCatFieldSchema.Type.TINYINT) {
      return n.byteValue();
    } else if (hfsType == HCatFieldSchema.Type.SMALLINT) {
      return n.shortValue();
    } else if (hfsType == HCatFieldSchema.Type.INT) {
      return n.intValue();
    } else if (hfsType == HCatFieldSchema.Type.BIGINT) {
      return n.longValue();
    } else if (hfsType == HCatFieldSchema.Type.FLOAT) {
      return n.floatValue();
    } else if (hfsType == HCatFieldSchema.Type.DOUBLE) {
      return n.doubleValue();
    } else if (hfsType == HCatFieldSchema.Type.BOOLEAN) {
      return n.byteValue() == 0 ? Boolean.FALSE : Boolean.TRUE;
    }
    return null;
  }
}
