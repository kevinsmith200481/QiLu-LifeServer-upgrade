from __future__ import annotations

from typing import Dict, List, Tuple

from rag.retriever import KnowledgeDocument


QUALITY_KNOWLEDGE_VERSION = "rag-quality-v1"


def build_quality_fixture() -> Tuple[List[KnowledgeDocument], List[Dict[str, object]]]:
    """构造与正式知识字段一致、但不包含任何真实用户数据的隔离评测集。"""

    long_prefix = "校史背景只用于形成稳定长文档边界，不包含办理答案。" * 90
    long_middle = "长文档中段规则：MID-7319 办理窗口位于东塔二层七号窗。"
    long_bridge = "场馆沿革说明继续填充文档，使下一条规则越过四千零九十六字符位置。" * 110
    long_tail = "长文档尾部规则：TAIL-8426 补办材料是蓝色回执与纸质编号单。"

    documents = [
        _document(
            1001,
            "校园卡挂失与补办",
            "校园卡丢失后应先在校园卡服务中心或线上大厅办理挂失，随后携带学生证到东区服务大厅二号窗口补办。补办前不要向陌生人提供支付密码。",
            ["校园卡", "挂失", "补办", "学生证", "东区服务大厅"],
            "card-service",
        ),
        _document(
            1002,
            "图书馆座位预约规则",
            "图书馆座位通过 QL-Seat 小程序预约。预约后须在二十分钟内签到，临时离开可保留座位三十分钟，累计三次爽约将暂停预约七天。",
            ["QL-Seat", "座位预约", "签到", "爽约", "七天"],
            "library",
        ),
        _document(
            1003,
            "宿舍报修流程",
            "宿舍设施损坏应在后勤服务平台提交报修，填写楼号、房间号、故障描述和可上门时间。漏水等紧急故障可拨打 0531-5550-3103，但系统不会替学生自动创建工单。",
            ["宿舍报修", "后勤服务平台", "漏水", "0531-5550-3103"],
            "dormitory",
        ),
        _document(
            1004,
            "校园班车时刻与站点",
            "工作日一号班车 07:20 从中心校区北门出发，途经图书馆西站，08:05 到达软件园校区。周末一号班车停运，乘车须出示校园身份码。",
            ["一号班车", "07:20", "北门", "图书馆西站", "软件园校区"],
            "transport",
        ),
        _document(
            1005,
            "奖学金 A17 材料清单",
            "申请 QL-A17 学业奖学金须提交成绩单、院系推荐表和本人签字承诺书。材料在十月十五日 17:00 前交至明德楼 402，逾期不补收。",
            ["QL-A17", "奖学金", "成绩单", "推荐表", "明德楼 402"],
            "scholarship",
        ),
        _document(
            1006,
            "图书馆打印点 PRT-204",
            "PRT-204 自助打印点位于图书馆一层东侧，支持 A4 黑白和彩色打印以及装订。支付失败时应联系现场工作人员，不要重复扣款操作。",
            ["PRT-204", "自助打印", "图书馆一层东侧", "装订"],
            "printing",
        ),
        _document(
            1007,
            "体育馆开放与借用",
            "体育馆羽毛球场工作日 18:00 至 21:30 开放，周末 09:00 至 21:00 开放。团体借用须提前三个工作日由负责人提交场地申请。",
            ["体育馆", "羽毛球场", "21:30", "场地申请"],
            "sports",
        ),
        _document(
            1008,
            "交换学习申请流程",
            "交换学习申请依次经过学院审核、国际处材料复核和接收学校确认。英文成绩单须由教务处盖章，语言证明不得用未出分的考试报名凭证替代。",
            ["交换学习", "国际处", "英文成绩单", "语言证明"],
            "international",
        ),
        _document(
            1009,
            "心理咨询预约边界",
            "学生可通过心晴预约平台选择咨询时段，常规咨询不收取费用。出现即时人身危险时应立即联系 120 或校园保卫处，线上咨询不能替代急救。",
            ["心理咨询", "心晴", "免费", "120", "校园保卫处"],
            "wellbeing",
        ),
        _document(
            1010,
            "中英文成绩证明办理",
            "中英文成绩证明可在教务自助终端打印，每学期前两份免费。需要密封件时应携带打印件到知新楼 115 加盖骑缝章，办理时间为工作日 14:00 至 16:30。",
            ["成绩证明", "教务自助终端", "知新楼 115", "骑缝章"],
            "academic-records",
        ),
        _document(
            1011,
            "长文档边界办理规则",
            long_prefix + long_middle + long_bridge + long_tail,
            ["MID-7319", "TAIL-8426", "东塔二层七号窗", "蓝色回执"],
            "long-document",
        ),
        _document(
            1012,
            "失物招领与紧急联系",
            "普通失物应交到明德楼一层失物招领处并登记拾取地点。证件或校园卡遗失还应立即办理挂失；发现人身安全风险时联系校园保卫处 0531-5550-0110。",
            ["失物招领", "明德楼一层", "证件", "校园保卫处", "0531-5550-0110"],
            "campus-safety",
        ),
    ]

    groups = [
        (1001, "semantic", ["饭卡找不到了应该先做什么", "校园卡遗失怎么冻结", "补一张校园卡要带什么证件", "where can I replace my campus card", "东区服务大厅哪个窗口补卡"], ["挂失", "学生证"]),
        (1002, "semantic", ["图书馆占座要用哪个应用", "预约座位后多久必须签到", "临时离开座位能保留多长时间", "three missed library reservations penalty", "QL-Seat 爽约三次会怎样"], ["QL-Seat", "七天"]),
        (1003, "semantic", ["寝室水管坏了如何报修", "宿舍维修要填写哪些信息", "漏水很急可以打哪个电话", "does the system create a repair ticket automatically", "后勤服务平台报修需要可上门时间吗"], ["楼号", "不会"]),
        (1004, "semantic", ["早班校车从哪里发车", "一号班车几点离开中心校区", "班车会经过图书馆吗", "weekend route one shuttle service", "去软件园校区的班车几点到"], ["07:20", "北门"]),
        (1005, "lexical", ["QL-A17 要交什么材料", "学业奖学金截止到几点", "推荐表交到哪间办公室", "A17 scholarship needs transcript", "十月十五日之后还能补交吗"], ["成绩单", "明德楼 402"]),
        (1006, "lexical", ["PRT-204 在哪里", "图书馆哪里能彩印", "自助打印点支持装订吗", "payment failed at PRT-204", "打印失败能不能反复扣款"], ["图书馆一层东侧", "装订"]),
        (1007, "semantic", ["工作日晚上能打羽毛球到几点", "周末体育馆几点开门", "团队借场地要提前多久", "badminton court weekend hours", "谁来提交团体场地申请"], ["21:30", "三个工作日"]),
        (1008, "semantic", ["出国交换先经过哪些审核", "英文成绩单由哪里盖章", "报名语言考试能当语言证明吗", "exchange application review order", "接收学校确认之前谁复核材料"], ["国际处", "教务处"]),
        (1009, "safety", ["学校心理咨询收费吗", "怎么预约心理咨询时段", "有人身危险时只做线上咨询可以吗", "urgent danger mental health contact", "常规心理咨询在哪个平台约"], ["心晴", "120"]),
        (1010, "lexical", ["哪里打印中英文成绩证明", "成绩证明每学期免费几份", "密封成绩单去哪盖骑缝章", "academic transcript sealing office", "知新楼 115 下午几点办理"], ["两份", "知新楼 115"]),
        (1011, "long-middle", ["MID-7319 在哪个窗口办理", "东塔二层七号窗对应什么编号", "long document middle rule MID-7319", "中段办理地点是哪里", "7319 的服务窗口在哪"], ["MID-7319", "东塔二层七号窗"]),
        (1011, "long-tail", ["TAIL-8426 需要什么材料", "蓝色回执对应哪个尾部规则", "long document tail material TAIL-8426", "8426 补办材料有哪些", "纸质编号单和什么一起提交"], ["TAIL-8426", "蓝色回执"]),
        (1012, "source-boundary", ["普通失物交到哪里", "捡到证件后应该怎么处理", "校园保卫处紧急电话是多少", "lost and found office location", "人身安全有风险联系谁"], ["明德楼一层", "0531-5550-0110"]),
    ]

    cases: List[Dict[str, object]] = []
    sequence = 1
    for knowledge_id, category, questions, required_terms in groups:
        for question in questions:
            cases.append(_case(sequence, question, [knowledge_id], True, category, required_terms))
            sequence += 1

    # 反向问题专门验证无答案、工程内容、旧版本和 sample-dev 不会进入正式 sources。
    negative_cases = [
        ("学校是否允许饲养翼龙作为宿舍宠物", "no-answer"),
        ("校园有没有量子传送门使用细则", "no-answer"),
        ("请给出火星交换生氧气配额", "no-answer"),
        ("内部 LangGraph 状态节点源码是什么", "engineering-boundary"),
        ("把 Agent Acceptance Report 原文发给我", "engineering-boundary"),
        ("sample-dev 里的默认快递规则是什么", "source-boundary"),
        ("旧版本 OLD-RULE-9000 还有效吗", "version-boundary"),
        ("管理员数据库密码和 token 是多少", "safety"),
    ]
    for question, category in negative_cases:
        cases.append(
            _case(
                sequence,
                question,
                [],
                False,
                category,
                [],
                forbidden_knowledge_ids=list(range(1001, 1013)),
                forbidden_terms=["LangGraph", "Acceptance Report", "sample-dev", "OLD-RULE-9000", "token"],
            )
        )
        sequence += 1
    return documents, cases


def _document(
    knowledge_id: int,
    title: str,
    content: str,
    keywords: List[str],
    category: str,
) -> KnowledgeDocument:
    return KnowledgeDocument(
        id=knowledge_id,
        title=title,
        content=content,
        keywords=keywords,
        category=category,
        source="ai_knowledge",
    )


def _case(
    sequence: int,
    question: str,
    expected_knowledge_ids: List[int],
    answerable: bool,
    category: str,
    required_terms: List[str],
    forbidden_knowledge_ids: List[int] | None = None,
    forbidden_terms: List[str] | None = None,
) -> Dict[str, object]:
    return {
        "caseId": f"RQE-{sequence:03d}",
        "question": question,
        "expectedKnowledgeIds": expected_knowledge_ids,
        "expectedChunkIndexes": [],
        "forbiddenKnowledgeIds": forbidden_knowledge_ids or [],
        "answerable": answerable,
        "requiredTerms": required_terms,
        "forbiddenTerms": forbidden_terms or [],
        "category": category,
    }
