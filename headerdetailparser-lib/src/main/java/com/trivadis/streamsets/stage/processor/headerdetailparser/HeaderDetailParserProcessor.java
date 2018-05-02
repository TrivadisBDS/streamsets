/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.trivadis.streamsets.stage.processor.headerdetailparser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.api.base.SingleLaneRecordProcessor;
import com.trivadis.streamsets.stage.lib.headerdetailparser.Errors;


public abstract class HeaderDetailParserProcessor extends SingleLaneRecordProcessor {
	private static final Logger LOG = LoggerFactory.getLogger(HeaderDetailParserProcessor.class);
	
	/**
	 * Gives access to the UI configuration of the stage provided by the
	 * {@link SampleDProcessor} class.
	 */
	public abstract String getFieldPathToParse();

	public abstract boolean isKeepOriginalFields();

	public abstract String getOutputField();
	
	public abstract String getDetailLineField();

	public abstract List<HeaderExtractorConfig> getHeaderExtractorConfigs();

	public abstract Integer getNofHeaderLines();

	// map of compiled regex, keyed by regex value
	private Map<String, Pattern> patterns = new HashMap<>();
	
	@Override
	protected List<ConfigIssue> init() {
		List<ConfigIssue> issues = super.init();
		
		// compile all patterns
		for (HeaderExtractorConfig headerExtractorConfig : getHeaderExtractorConfigs()) {
			patterns.put(headerExtractorConfig.regex, Pattern.compile(headerExtractorConfig.regex));
		}

		return issues;
	}

	/** {@inheritDoc} */
	@Override
	protected void process(Record record, SingleLaneBatchMaker batchMaker) throws StageException {
	    if (isKeepOriginalFields() && !record.get().getType().isOneOf(Field.Type.MAP, Field.Type.LIST_MAP)) {
	        String errorValue;
	        if (record.get().getType() == Field.Type.LIST) {
	          errorValue = record.get().getValueAsList().toString();
	        } else {
	          errorValue = record.get().toString();
	        }
	        throw new OnRecordErrorException(
	            Errors.HEADERDETAILP_02, record.get().getType().toString(),
	            errorValue, record.toString());
	    }
	    
		Field original = record.get(getFieldPathToParse());
		if (original != null) {
	        if (original.getType() != Field.Type.STRING) {
	            throw new OnRecordErrorException(
	                Errors.HEADERDETAILP_02, original.getType().toString(), original.getValue().toString(), record.toString());
	        }
	        
	        processOriginal(original, record, batchMaker);
		}

	}

	private void processOriginal(Field original, Record orinalRecord, SingleLaneBatchMaker batchMaker) {
		String originalValue = original.getValueAsString().trim();

        Record record = null;
        
        if (isKeepOriginalFields()) {
        	record = getContext().cloneRecord(orinalRecord);
        } else {
        	record = getContext().createRecord(orinalRecord);
            record.set(Field.create(new HashMap<String, Field>()));
        }
		
		String[] lines = StringUtils.split(originalValue, StringUtils.LF);

		List<String> headers = new ArrayList<>();
		List<String> details = new ArrayList<>();
		List<Field> detailsField = new ArrayList<>();
		String columnHeader = null;

		boolean withinHeader = true;
		boolean parseDetailHeader = true;
		for (int i = 0; i < lines.length; i++) {
			if (withinHeader) {
				if (lines[i].equals("-----")) {
					withinHeader = false;
				} else if (getNofHeaderLines() == null || i >= getNofHeaderLines()) {
					withinHeader = false;
				} else {
					headers.add(lines[i]);
				}
			} else {
				if (parseDetailHeader) {
					columnHeader = lines[i];
					parseDetailHeader = false;
				} else {
					details.add(lines[i]);
					detailsField.add(Field.create(lines[i]));
				}
			}
		}
		
		LOG.info("Number of header lines: " + headers.size());
		LOG.info("Number of detail lines: " + details.size());

		for (HeaderExtractorConfig headerExtractorConfig : getHeaderExtractorConfigs()) {
			// record.set("/type", Field.create("HEADER"));

			String header = headers.get(headerExtractorConfig.lineNumber-1);
			LOG.info("header: " + header);
			LOG.info("regex: " + headerExtractorConfig.regex);
			Matcher matcher = getPattern(patterns, headerExtractorConfig.regex).matcher(header);
			if (matcher.find()) {
				LOG.info("matcher found");
				String key = headerExtractorConfig.key;
				// take the value from group 1, if no key is specified
				if (headerExtractorConfig.key == null || headerExtractorConfig.key.length() == 0) {
					key = matcher.group(1);
				}
				LOG.info("key: " + key);
				String val = matcher.group(2);
				LOG.info("val: " + val);
				record.set("/" + key, Field.create(val));
			} else {
				LOG.info("no matcher found: " + matcher);
			}
					
		}
		
		for (String detail : details) {
			record.set("/" + getDetailLineField(), Field.create(detail));
			batchMaker.addRecord(record);
		}
		
	}
	
	private static Pattern getPattern(Map<String, Pattern> patterns, String regEx) {
		if (patterns != null && patterns.containsKey(regEx)) {
			return patterns.get(regEx);
		} else {
			Pattern pattern = Pattern.compile(regEx);
			if (patterns != null) {
				patterns.put(regEx, pattern);
			}
			return pattern;
		}
	}
}