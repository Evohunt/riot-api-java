/*
 * Copyright 2016 Taylor Caldwell
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.rithms.riot.api.endpoints.league.methods;

import java.util.List;

import com.google.gson.reflect.TypeToken;

import net.rithms.riot.api.ApiConfig;
import net.rithms.riot.api.endpoints.league.LeagueApiMethod;
import net.rithms.riot.api.endpoints.league.constant.LeagueQueue;
import net.rithms.riot.constant.Platform;

public class GetPositionalRankQueues extends LeagueApiMethod {

	public GetPositionalRankQueues(ApiConfig config, Platform platform) {
		super(config);
		setPlatform(platform);
		setReturnType(new TypeToken<List<LeagueQueue>>() {
		}.getType());
		setUrlBase(platform.getHost() + "/lol/league/v4/positional-rank-queues");
		addApiKeyParameter();
	}
}