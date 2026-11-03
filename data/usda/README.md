# USDA食品数据来源

- 发布方：U.S. Department of Agriculture, Agricultural Research Service。
- 数据集：FoodData Central SR Legacy，2018-04。
- 官方许可说明：https://fdc.nal.usda.gov/api-guide/#bkmk-6
- 官方下载目录：https://fdc.nal.usda.gov/download-datasets/
- 原始归档：https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip
- 许可：CC0 1.0；USDA要求使用者注明FoodData Central来源。

原始归档下载至被Git忽略的.tools；生成的最小原始摘录及转换结果可以随仓库分发。中文名称与别名是本项目维护的映射，不属于USDA原始字段；保留英文描述以便核查。

数据为美国参考食品条目，不代表本地每一份食物的实际检测结果。SR Legacy是历史固定版本；选择它便于复现，不声称是最新数据。重量只按可食部克重换算，不自行推算数量或生熟转换。

2026-11-03更新：应用已通过JsonFoodCatalog读取本目录摘录，Maven构建时将其纳入Jar。默认运行时不再使用演示目录；尚未接入数据库或正式Agent。

## 首批样本与复现

运行 `python3 scripts/import-usda.py` 从.tools/usda-sr2018.zip生成selected-foods.json。执行 `python3 scripts/test_usda_import.py` 验证缺失/零值、单位、重复营养项和描述变化保护。

摘录保存食品原始描述、发布字段、营养原始行、营养素定义、归档SHA256。营养数值用十进制字符串保留精度，未提供的项目为null。能量仅取1008/KCAL，不拿KJ替代；不反推缺失热量。

中文映射：169756长粒白米（生，未强化）；168935长粒白米饭（熟，未强化，加盐）；171287全鸡蛋（生，鲜）。加盐、强化和生熟条件不可省略；样本不是泛指所有米饭或鸡蛋制品。

归档名标记2018-04，原始food.csv中的publication_date为2019-04-01，两个字段均保留，不混为同一日期。下载核实日期2026-11-03。

4项导入保护测试通过，重复生成字节一致。原始ZIP CRC读取成功；归档SHA256是本次下载内容的本地指纹，不代表独立取得官方签名。
