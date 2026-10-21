"""생성 파일 — 손으로 고치지 말 것. scripts/store_category_sync.py 가 BE StoreCategory.java 에서 만든다.

BE bbang-ggut_BE-axis origin/dev @ e5a48eb0 · 2026-09-14 · 코드 21종 · 외부 문자열 81개
"""
BE_COMMIT = "e5a48eb0"
CODES = ['BAKERY', 'CAFE_DESSERT', 'CAKE', 'DONUT', 'BAGEL', 'SANDWICH', 'BRUNCH', 'WAFFLE_CREPE', 'CHOCOLATE', 'RICE_CAKE', 'WALNUT_CAKE', 'HOTTEOK', 'STEAMED_BUN', 'ICE_CREAM', 'BEVERAGE', 'RESTAURANT', 'PUB', 'GROCERY', 'WORKSHOP', 'EDUCATION', 'OTHER']
EXTERNAL_TO_CODE = {
    # BAKERY
    '베이커리': 'BAKERY',
    '빵류제조': 'BAKERY',
    '제과,제빵': 'BAKERY',
    '과자류제조': 'BAKERY',
    '경주빵': 'BAKERY',
    '커피번': 'BAKERY',
    '제과,베이커리': 'BAKERY',
    # CAFE_DESSERT
    '카페,디저트': 'CAFE_DESSERT',
    '카페': 'CAFE_DESSERT',
    '디저트 팝업': 'CAFE_DESSERT',
    '테이크아웃커피': 'CAFE_DESSERT',
    '플라워카페': 'CAFE_DESSERT',
    '갤러리카페': 'CAFE_DESSERT',
    '북카페': 'CAFE_DESSERT',
    '테마카페': 'CAFE_DESSERT',
    '디저트카페': 'CAFE_DESSERT',
    '커피전문점': 'CAFE_DESSERT',
    # CAKE
    '케이크전문': 'CAKE',
    # DONUT
    '도넛': 'DONUT',
    # BAGEL
    '베이글': 'BAGEL',
    # SANDWICH
    '샌드위치': 'SANDWICH',
    '토스트': 'SANDWICH',
    '핫도그': 'SANDWICH',
    '햄버거': 'SANDWICH',
    # BRUNCH
    '브런치카페': 'BRUNCH',
    '브런치': 'BRUNCH',
    # WAFFLE_CREPE
    '와플': 'WAFFLE_CREPE',
    '크레페': 'WAFFLE_CREPE',
    # CHOCOLATE
    '초콜릿전문점': 'CHOCOLATE',
    '과자,사탕,초코렛': 'CHOCOLATE',
    # RICE_CAKE
    '떡,한과': 'RICE_CAKE',
    '떡류제조': 'RICE_CAKE',
    '떡카페': 'RICE_CAKE',
    # WALNUT_CAKE
    '호두과자': 'WALNUT_CAKE',
    # HOTTEOK
    '호떡': 'HOTTEOK',
    # STEAMED_BUN
    '찐빵': 'STEAMED_BUN',
    '만두': 'STEAMED_BUN',
    '칼국수,만두': 'STEAMED_BUN',
    # ICE_CREAM
    '아이스크림': 'ICE_CREAM',
    '빙수': 'ICE_CREAM',
    # BEVERAGE
    '차': 'BEVERAGE',
    '차,커피': 'BEVERAGE',
    '홍차전문점': 'BEVERAGE',
    '과일,주스전문점': 'BEVERAGE',
    '다방': 'BEVERAGE',
    # RESTAURANT
    '양식': 'RESTAURANT',
    '한식': 'RESTAURANT',
    '일식당': 'RESTAURANT',
    '중식당': 'RESTAURANT',
    '종합분식': 'RESTAURANT',
    '분식': 'RESTAURANT',
    '떡볶이': 'RESTAURANT',
    '이탈리아음식': 'RESTAURANT',
    '스파게티,파스타전문': 'RESTAURANT',
    '프랑스음식': 'RESTAURANT',
    '피자': 'RESTAURANT',
    '국수': 'RESTAURANT',
    '다이어트,샐러드': 'RESTAURANT',
    '도시락,컵밥': 'RESTAURANT',
    # PUB
    '바(BAR)': 'PUB',
    '맥주,호프': 'PUB',
    '와인': 'PUB',
    '요리주점': 'PUB',
    '이자카야': 'PUB',
    # GROCERY
    '가공식품': 'GROCERY',
    '식료품': 'GROCERY',
    '식료품제조': 'GROCERY',
    '유기농산물마트': 'GROCERY',
    '슈퍼,마트': 'GROCERY',
    '편의점': 'GROCERY',
    '종합도소매': 'GROCERY',
    '반찬가게': 'GROCERY',
    # WORKSHOP
    '공방': 'WORKSHOP',
    # EDUCATION
    '요리교육': 'EDUCATION',
    '취미교육': 'EDUCATION',
    '놀이교육': 'EDUCATION',
    '스포츠,레크레이션교육': 'EDUCATION',
    '학원 등 교습시설': 'EDUCATION',
    '체험,홍보관': 'EDUCATION',
    '체험마을': 'EDUCATION',
    '관람,체험': 'EDUCATION',
}


def category_of(raw):
    """BE StoreCategory.fromExternalName 과 같은 규칙. 비어 있으면 None(미수집), 매핑 없으면 OTHER."""
    if raw is None or not raw.strip():
        return None
    for node in reversed(raw.split(">")):
        code = EXTERNAL_TO_CODE.get(node.strip())
        if code:
            return code
    return "OTHER"
