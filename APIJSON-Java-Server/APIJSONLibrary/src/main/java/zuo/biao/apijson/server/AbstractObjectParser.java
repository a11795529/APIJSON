/*Copyright ©2016 TommyLemon(https://github.com/TommyLemon/APIJSON)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.*/

package zuo.biao.apijson.server;

import static zuo.biao.apijson.JSONObject.KEY_CONDITION;
import static zuo.biao.apijson.JSONObject.KEY_CORRECT;
import static zuo.biao.apijson.JSONObject.KEY_DROP;
import static zuo.biao.apijson.JSONObject.KEY_TRY;
import static zuo.biao.apijson.RequestMethod.PUT;
import static zuo.biao.apijson.server.SQLConfig.TYPE_ITEM;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import zuo.biao.apijson.Log;
import zuo.biao.apijson.RequestMethod;
import zuo.biao.apijson.StringUtil;
import zuo.biao.apijson.server.exception.ConflictException;
import zuo.biao.apijson.server.exception.NotExistException;


/**简化Parser，getObject和getArray(getArrayConfig)都能用
 * @author Lemon
 */
public abstract class AbstractObjectParser implements ObjectParser {
	private static final String TAG = "ObjectParser";

	@NotNull
	protected Parser parser;
	public AbstractObjectParser setParser(Parser parser) {
		this.parser = parser;
		return this;
	}


	protected JSONObject request;//不用final是为了recycle
	protected String parentPath;//不用final是为了recycle
	protected SQLConfig arrayConfig;//不用final是为了recycle

	protected final int type;
	protected final boolean isTable;
	protected final String path;
	protected final String table;


	protected final boolean tri;
	/**
	 * TODO Parser内要不因为 非 TYPE_ITEM_CHILD_0 的Table 为空导致后续中断。
	 */
	protected final boolean drop;
	protected JSONObject correct;

	/**for single object
	 * @param parentPath
	 * @param request
	 * @param name
	 * @throws Exception 
	 */
	public AbstractObjectParser(@NotNull JSONObject request, String parentPath, String name, SQLConfig arrayConfig) throws Exception {
		if (request == null) {
			throw new IllegalArgumentException(TAG + ".ObjectParser  request == null!!!");
		}
		this.request = request;
		this.parentPath = parentPath;
		this.arrayConfig = arrayConfig;

		this.type = arrayConfig == null ? 0 : arrayConfig.getType();
		this.path = AbstractParser.getAbsPath(parentPath, name);
		this.table = Pair.parseEntry(name, true).getKey();
		this.isTable = zuo.biao.apijson.JSONObject.isTableKey(table);

		boolean isEmpty = request.isEmpty();//empty有效 User:{}
		if (isEmpty) {
			this.tri = false;
			this.drop = false;
		} else {
			this.tri = request.getBooleanValue(KEY_TRY);
			this.drop = request.getBooleanValue(KEY_DROP);
			this.correct = request.getJSONObject(KEY_CORRECT);

			request.remove(KEY_TRY);
			request.remove(KEY_DROP);
			request.remove(KEY_CORRECT);

			try {
				parseCorrect();
			} catch (Exception e) {
				if (tri == false) {
					throw e;
				}
				invalidate();
			}
		}


		Log.d(TAG, "ObjectParser  table = " + table + "; isTable = " + isTable);
		Log.d(TAG, "ObjectParser  isEmpty = " + isEmpty + "; tri = " + tri + "; drop = " + drop);
	}

	public static final Map<String, Pattern> COMPILE_MAP;
	static {
		COMPILE_MAP = new HashMap<String, Pattern>();
	}

	protected Map<String, String> corrected;
	/**解析 @correct 校正
	 * @throws Exception 
	 */
	@Override
	public AbstractObjectParser parseCorrect() throws Exception {
		Set<String> set = correct == null ? null : new HashSet<>(correct.keySet());

		if (set != null && set.isEmpty() == false) {//对每个需要校正的key进行正则表达式匹配校正
			corrected = new HashMap<>();//TODO 返回全部correct内的内容，包括未校正的?  correct);

			String value; //13000082001
			String v; // phone,email,idCard
			String[] posibleKeys; //[phone,email,idCard]

			for (String k : set) {// k = cert
				v = k == null ? null : correct.getString(k);
				value = v == null ? null : request.getString(k);
				posibleKeys = value == null ? null : StringUtil.split(v);

				if (posibleKeys != null && posibleKeys.length > 0) {
					String rk = null;
					Pattern p;
					for (String pk : posibleKeys) {
						p = pk == null ? null : COMPILE_MAP.get(pk);
						if (p != null && p.matcher(value).matches()) {
							rk = pk;
							break;
						}
					}

					if (rk == null) {
						throw new IllegalArgumentException(
								"格式错误！找不到 " + k + ":" + value + " 对应[" + v + "]内的任何一项！");
					}
					request.put(rk, request.remove(k));
					corrected.put(k, rk);
				}
			}
		}

		return this;
	}



	private boolean invalidate = false;
	public void invalidate() {
		invalidate = true;
	}
	public boolean isInvalidate() {
		return invalidate;
	}

	private boolean breakParse = false;
	public void breakParse() {
		breakParse = true;
	}
	public boolean isBreakParse() {
		return breakParse || isInvalidate();
	}


	protected JSONObject response;
	protected JSONObject sqlRequest;
	protected JSONObject sqlReponse;
	protected Map<String, Object> customMap;
	protected Map<String, String> functionMap;
	protected Map<String, JSONObject> childMap;

	/**解析成员
	 * response重新赋值
	 * @return null or this
	 * @throws Exception
	 */
	@Override
	public AbstractObjectParser parse() throws Exception {
		if (isInvalidate() == false) {
			breakParse = false;

			response = new JSONObject(true);//must init

			sqlRequest = new JSONObject(true);//must init
			sqlReponse = null;//must init
			customMap = null;//must init
			functionMap = null;//must init
			childMap = null;//must init

			Set<Entry<String, Object>> set = new LinkedHashSet<Entry<String, Object>>(request.entrySet());
			if (set != null && set.isEmpty() == false) {//判断换取少几个变量的初始化是否值得？
				if (isTable) {//非Table下不必分离出去再添加进来
					customMap = new LinkedHashMap<String, Object>();
					childMap = new LinkedHashMap<String, JSONObject>();
				}
				functionMap = new LinkedHashMap<String, String>();//必须执行


				//条件<<<<<<<<<<<<<<<<<<<
				List<String> conditionList = null;
				if (method == PUT) { //这里只有PUTArray需要处理  || method == DELETE) {
					String[] conditions = StringUtil.split(request.getString(KEY_CONDITION));
					//Arrays.asList()返回值不支持add方法！
					conditionList = new ArrayList<String>(Arrays.asList(conditions != null ? conditions : new String[]{}));
					conditionList.add(zuo.biao.apijson.JSONRequest.KEY_ID);
					conditionList.add(zuo.biao.apijson.JSONRequest.KEY_ID_IN);
				}
				//条件>>>>>>>>>>>>>>>>>>>

				String key;
				Object value;
				int index = 0;
				for (Entry<String, Object> entry : set) {
					if (isBreakParse()) {
						break;
					}

					value = entry.getValue();
					if (value == null) {
						continue;
					}
					key = entry.getKey();

					try {
						if (value instanceof JSONObject && key.startsWith("@") == false) {//JSONObject，往下一级提取
							if (childMap != null) {//添加到childMap，最后再解析
								childMap.put(key, (JSONObject)value);
							} else {//直接解析并替换原来的
								response.put(key, onChildParse(index, key, (JSONObject)value));
								index ++;
							}
						}
						else if (method == PUT && value instanceof JSONArray
								&& (conditionList == null || conditionList.contains(key) == false)) {//PUT JSONArray
							onPUTArrayParse(key, (JSONArray) value);
						}
						else {//JSONArray或其它Object，直接填充
							if (onParse(key, value) == false) {
								invalidate();
							}
						}
					} catch (Exception e) {
						if (tri == false) {
							throw e;//不忽略错误，抛异常
						}
						invalidate();//忽略错误，还原request
					}
				}
			}
		}

		if (isInvalidate()) {
			recycle();
			return null;
		}

		return this;
	}





	/**解析普通成员
	 * @param key
	 * @param value
	 * @return whether parse succeed
	 */
	@Override
	public boolean onParse(@NotNull String key, @NotNull Object value) throws Exception {
		if (key.endsWith("@")) {//StringUtil.isPath((String) value)) {
			if (value instanceof String == false) {
				throw new IllegalArgumentException("\"key@\": 后面必须为依赖路径String！");
			}
			//						System.out.println("getObject  key.endsWith(@) >> parseRelation = " + parseRelation);
			String replaceKey = key.substring(0, key.length() - 1);//key{}@ getRealKey
			String targetPath = AbstractParser.getValuePath(type == TYPE_ITEM
					? path : parentPath, new String((String) value));

			//先尝试获取，尽量保留缺省依赖路径，这样就不需要担心路径改变
			Object target = onReferenceParse(targetPath);
			Log.i(TAG, "onParse targetPath = " + targetPath + "; target = " + target);

			if (target == null) {//String#equals(null)会出错
				Log.d(TAG, "onParse  target == null  >>  continue;");
				return true;
			}
			if (target instanceof Map) { //target可能是从requestObject里取出的 {}
				Log.d(TAG, "onParse  target instanceof Map  >>  continue;");
				return false;
			}
			if (targetPath.equals(target)) {//必须valuePath和保证getValueByPath传进去的一致！
				Log.d(TAG, "onParse  targetPath.equals(target)  >>");

				//非查询关键词 @key 不影响查询，直接跳过
				if (isTable && (key.startsWith("@") == false || JSONRequest.TABLE_KEY_LIST.contains(key))) {
					Log.e(TAG, "onParse  isTable && (key.startsWith(@) == false"
							+ " || JSONRequest.TABLE_KEY_LIST.contains(key)) >>  return null;");
					return false;//获取不到就不用再做无效的query了。不考虑 Table:{Table:{}}嵌套
				} else {
					Log.d(TAG, "onParse  isTable(table) == false >> continue;");
					return true;//舍去，对Table无影响
				}
			} 


			//直接替换原来的key@:path为key:target
			Log.i(TAG, "onParse    >>  key = replaceKey; value = target;");
			key = replaceKey;
			value = target;
			Log.d(TAG, "onParse key = " + key + "; value = " + value);
		}

		if (key.endsWith("()")) {
			if (value instanceof String == false) {
				throw new IllegalArgumentException(path + "/" + key + "():function() 后面必须为函数String！");
			}
			functionMap.put(key, (String) value);
		} else if (isTable && key.startsWith("@") && JSONRequest.TABLE_KEY_LIST.contains(key) == false) {
			customMap.put(key, value);
		} else {
			sqlRequest.put(key, value);
		}

		return true;
	}



	/**
	 * @param key
	 * @param value
	 * @param isFirst 
	 * @return
	 * @throws Exception
	 */
	@Override
	public JSON onChildParse(int index, String key, JSONObject value) throws Exception {
		boolean isFirst = index <= 0;
		boolean isMain = isFirst && type == TYPE_ITEM;

		JSON child;
		boolean isEmpty;
		
		if (zuo.biao.apijson.JSONObject.isArrayKey(key)) {//APIJSON Array
			if (isMain) {
				throw new IllegalArgumentException(parentPath + "/" + key + ":{} 不合法！"
						+ "数组 []:{} 中第一个 key:{} 必须是主表 TableKey:{} ！不能为 arrayKey[]:{} ！");
			}
			
			child = parser.onArrayParse(value, path, key);
			isEmpty = child == null || ((JSONArray) child).isEmpty();
		}
		else {//APIJSON Object
			if (type == TYPE_ITEM && JSONRequest.isTableKey(Pair.parseEntry(key, true).getKey()) == false) {
				throw new IllegalArgumentException(parentPath + "/" + key + ":{} 不合法！"
						+ "数组 []:{} 中每个 key:{} 都必须是表 TableKey:{} 或 数组 arrayKey[]:{} ！");
			}
			
			child = parser.onObjectParse(value, path, key, isMain ? arrayConfig.setType(SQLConfig.TYPE_ITEM_CHILD_0) : null);
			
			isEmpty = child == null || ((JSONObject) child).isEmpty();
			if (isFirst && isEmpty) {
				invalidate();
			}
		}
		Log.i(TAG, "onChildParse  ObjectParser.onParse  key = " + key + "; child = " + child);

		return isEmpty ? null : child;//只添加! isChildEmpty的值，可能数据库返回数据不够count
	}



	//TODO 改用 MySQL json_add,json_remove,json_contains 等函数！ 
	/**PUT key:[]
	 * @param key
	 * @param array
	 * @throws Exception
	 */
	@Override
	public void onPUTArrayParse(@NotNull String key, @NotNull JSONArray array) throws Exception {
		if (isTable == false || array.isEmpty()) {
			Log.e(TAG, "onPUTArrayParse  isTable == false || array == null || array.isEmpty() >> return;");
			return;
		}

		int putType = 0;
		if (key.endsWith("+")) {//add
			putType = 1;
		} else if (key.endsWith("-")) {//remove
			putType = 2;
		} else {//replace
			//			throw new IllegalAccessException("PUT " + path + ", PUT Array不允许 " + key + 
			//					" 这种没有 + 或 - 结尾的key！不允许整个替换掉原来的Array！");
		}
		String realKey = AbstractSQLConfig.getRealKey(method, key, false, false);

		//GET > add all 或 remove all > PUT > remove key

		//GET <<<<<<<<<<<<<<<<<<<<<<<<<
		JSONObject rq = new JSONObject();
		rq.put(JSONRequest.KEY_ID, request.get(JSONRequest.KEY_ID));
		rq.put(JSONRequest.KEY_COLUMN, realKey);
		JSONObject rp = parseResponse(new JSONRequest(table, rq));
		//GET >>>>>>>>>>>>>>>>>>>>>>>>>


		//add all 或 remove all <<<<<<<<<<<<<<<<<<<<<<<<<
		if (rp != null) {
			rp = rp.getJSONObject(table);
		}
		JSONArray targetArray = rp == null ? null : rp.getJSONArray(realKey);
		if (targetArray == null) {
			targetArray = new JSONArray();
		}
		for (Object obj : array) {
			if (obj == null) {
				continue;
			}
			if (putType == 1) {
				if (targetArray.contains(obj)) {
					throw new ConflictException("PUT " + path + ", " + realKey + ":" + obj + " 已存在！");
				}
				targetArray.add(obj);
			} else if (putType == 2) {
				if (targetArray.contains(obj) == false) {
					throw new NullPointerException("PUT " + path + ", " + realKey + ":" + obj + " 不存在！");
				}
				targetArray.remove(obj);
			}
		}

		//add all 或 remove all >>>>>>>>>>>>>>>>>>>>>>>>>

		//PUT <<<<<<<<<<<<<<<<<<<<<<<<<
		sqlRequest.put(realKey, targetArray);
		//PUT >>>>>>>>>>>>>>>>>>>>>>>>>

	}


	/**SQL查询，for single object
	 * @return {@link #executeSQL(int, int, int)}
	 * @throws Exception
	 */
	@Override
	public AbstractObjectParser executeSQL() throws Exception {
		return executeSQL(1, 0, 0);
	}

	protected SQLConfig sqlConfig = null;//array item复用
	/**SQL查询，for array item
	 * @param count
	 * @param page
	 * @param position
	 * @return this
	 * @throws Exception
	 */
	@Override
	public AbstractObjectParser executeSQL(int count, int page, int position) throws Exception {
		//执行SQL操作数据库
		if (isTable == false) {//提高性能
			sqlReponse = new JSONObject(sqlRequest);
		} else {

			try {
				if (sqlConfig == null) {
					sqlConfig = newSQLConfig();
				}
				sqlConfig.setCount(count).setPage(page).setPosition(position);
				sqlReponse = onSQLExecute();
			} catch (Exception e) {
				Log.e(TAG, "getObject  try { response = getSQLObject(config2); } catch (Exception e) {");
				if (e instanceof NotExistException) {//非严重异常，有时候只是数据不存在
					//						e.printStackTrace();
					sqlReponse = null;//内部吃掉异常，put到最外层
					//						requestObject.put(JSONResponse.KEY_MSG
					//								, StringUtil.getString(requestObject.get(JSONResponse.KEY_MSG)
					//										+ "; query " + path + " cath NotExistException:"
					//										+ newErrorResult(e).getString(JSONResponse.KEY_MSG)));
				} else {
					throw e;
				}
			}

			if (drop) {//丢弃Table，只为了向下提供条件
				sqlReponse = null;
			}
		}

		return this;
	}

	/**
	 * @return response
	 * @throws Exception
	 */
	@Override
	public JSONObject response() throws Exception {
		if (sqlReponse == null || sqlReponse.isEmpty()) {
			if (isTable) {//Table自身都获取不到值，则里面的Child都无意义，不需要再解析
				return response;
			}
		} else {
			response.putAll(sqlReponse);
		}


		//把已校正的字段键值对corrected<originKey, correctedKey>添加进来，还是correct直接改？
		if (corrected != null) {
			response.put(KEY_CORRECT, corrected);
		}

		//把isTable时取出去的custom重新添加回来
		if (customMap != null) {
			response.putAll(customMap);
		}



		//解析函数function
		if (functionMap != null) {
			Set<Entry<String, String>> functionSet = functionMap == null ? null : functionMap.entrySet();
			if (functionSet != null && functionSet.isEmpty() == false) {
				for (Entry<String, String> entry : functionSet) {
					response.put(AbstractSQLConfig.getRealKey(method, entry.getKey(), false, false)
							, onFunctionParse(response, entry.getValue()));
				}
			}
		}

		//把isTable时取出去child解析后重新添加回来
		Set<Entry<String, JSONObject>> set = childMap == null ? null : childMap.entrySet();
		if (set != null) {
			int index = 0;
			for (Entry<String, JSONObject> entry : set) {
				if (entry != null) {
					response.put(entry.getKey(), onChildParse(index, entry.getKey(), entry.getValue()));
					index ++;
				}
			}
		}

		onComplete();

		return response;
	}

	@Override
	public Object onFunctionParse(JSONObject json, String function) throws Exception {
		return null;
	}
	
	@Override
	public Object onReferenceParse(@NotNull String path) {
		return parser.getValueByPath(path);
	}

	@Override
	public JSONObject onSQLExecute() throws Exception {
		JSONObject result = parser.executeSQL(sqlConfig);
		if (result != null) {
			parser.putQueryResult(path, result);//解决获取关联数据时requestObject里不存在需要的关联数据
		}
		return result;
	}


	/**
	 * response has the final value after parse (and query if isTable)
	 */
	@Override
	public void onComplete() {
	}


	/**回收内存
	 */
	@Override
	public void recycle() {
		//后面还可能用到，要还原
		if (tri) {//避免返回未传的字段
			request.put(KEY_TRY, tri);
		}
		if (drop) {
			request.put(KEY_DROP, drop);
		}
		if (correct != null) {
			request.put(KEY_CORRECT, correct);
		}


		correct = null;
		corrected = null;
		method = null;
		parentPath = null;
		arrayConfig = null;

		//		if (response != null) {
		//			response.clear();//有效果?
		//			response = null;
		//		}

		request = null;
		response = null;
		sqlRequest = null;
		sqlReponse = null;

		functionMap = null;
		customMap = null;
		childMap = null;
	}






	protected RequestMethod method;
	@Override
	public AbstractObjectParser setMethod(RequestMethod method) {
		if (this.method != method) {
			this.method = method;
			sqlConfig = null;
			//TODO ?			sqlReponse = null;
		}
		return this;
	}
	@Override
	public RequestMethod getMethod() {
		return method;
	}




	@Override
	public boolean isTable() {
		return isTable;
	}
	@Override
	public String getPath() {
		return path;
	}
	@Override
	public String getTable() {
		return table;
	}
	@Override
	public SQLConfig getArrayConfig() {
		return arrayConfig;
	}


	@Override
	public SQLConfig getSQLConfig() {
		return sqlConfig;
	}

	@Override
	public JSONObject getResponse() {
		return response;
	}
	@Override
	public JSONObject getSqlRequest() {
		return sqlRequest;
	}
	@Override
	public JSONObject getSqlReponse() {
		return sqlReponse;
	}

	@Override
	public Map<String, Object> getCustomMap() {
		return customMap;
	}
	@Override
	public Map<String, String> getFunctionMap() {
		return functionMap;
	}
	@Override
	public Map<String, JSONObject> getChildMap() {
		return childMap;
	}


}
