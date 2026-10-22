package dev.zhanqu.app.core

object BuiltInProfiles {
    /** IDs are provisional for XHS 9.49.2, not a promise of compatibility with other releases. */
    fun xiaohongshu(): CaptureProfile = CaptureProfile.fromJson(XIAOHONGSHU_JSON)
    val XIAOHONGSHU_JSON = """
    {
      "schemaVersion":1,
      "id":"xiaohongshu.image-note.v1",
      "packageName":"com.xingin.xhs",
      "activityPatterns":[".*NoteDetailActivity${'$'}"],
      "priority":10,
      "recognition":{"all":[
        {"exists":{"viewId":"imageListView"}},
        {"exists":{"descRegex":"图片[,，]第\\s*(\\d+)张[,，]共\\s*(\\d+)张"}}
      ]},
      "locators":{
        "carousel":[{"viewId":"imageListView","requireVisibleBounds":true}],
        "currentImage":[{"viewId":"photoImageView","ancestorViewId":"imageListView","requireVisibleBounds":true},{"viewId":"photoImageView","requireVisibleBounds":true}],
        "pageIndicator":[{"descRegex":"图片[,，]第\\s*(\\d+)张[,，]共\\s*(\\d+)张","requireVisibleBounds":true}],
        "title":[{"viewId":"noteTitleTV"},{"viewId":"noteTitle"},{"viewId":"title"},{"viewId":"tv_title"}],
        "author":[{"viewId":"nickname"},{"viewId":"nickNameTV"},{"viewId":"tv_nickname"}],
        "body":[{"viewId":"imageNoteTextView"},{"viewId":"noteContent"},{"viewId":"desc"}],
        "displayedCommentCount":[{"textRegex":"共\\s*[0-9]+\\s*条评论"}],
        "noteScroll":[{"viewId":"nestedScrollLayout","requireVisibleBounds":true},{"viewId":"nested_header","requireVisibleBounds":true},{"viewId":"noteContentLayout","requireVisibleBounds":true},{"viewId":"noteContent","ancestorViewId":"noteDetailRoot","requireVisibleBounds":true}],
        "commentEntry":[{"viewId":"noteCommentLayout","ancestorViewId":"engageBarLayout","clickable":true,"requireVisibleBounds":true}],
        "commentList":[{"viewId":"noteDetailRV"}],
        "mainComment":[{"viewId":"parentCommentLayout","ancestorViewId":"noteDetailRV"}],
        "subComment":[{"viewId":"subCommentLayout","ancestorViewId":"noteDetailRV"}],
        "commentText":[{"viewId":"tv_content","ancestorViewId":"noteDetailRV"}],
        "commentAuthor":[{"viewId":"tv_user_name","ancestorViewId":"noteDetailRV"},{"viewId":"tv_nickName","ancestorViewId":"noteDetailRV"},{"viewId":"tv_nickname","ancestorViewId":"noteDetailRV"},{"viewId":"nickNameTV","ancestorViewId":"noteDetailRV"},{"viewId":"nickname","ancestorViewId":"noteDetailRV"}],
        "commentTime":[{"viewId":"newTimePoiIpTransTv","ancestorViewId":"noteDetailRV"},{"viewId":"tv_time","ancestorViewId":"noteDetailRV"},{"viewId":"timeTV","ancestorViewId":"noteDetailRV"}],
        "commentRegion":[{"viewId":"tv_location","ancestorViewId":"noteDetailRV"},{"viewId":"locationTV","ancestorViewId":"noteDetailRV"}],
        "commentLikes":[{"viewId":"tv_like_num_new_engage","ancestorViewId":"noteDetailRV"},{"viewId":"likeCountTV","ancestorViewId":"noteDetailRV"},{"viewId":"tv_like_count","ancestorViewId":"noteDetailRV"}],
        "expandReplies":[{"viewId":"loadMoreTV","textRegex":"(展开|更多|加载).*回复","ancestorViewId":"noteDetailRV"}],
        "commentEnd":[{"textRegex":"^[-\\s—·]*(没有更多评论了|没有更多了|已显示全部评论|到底了)[-\\s—·]*${'$'}","ancestorViewId":"noteDetailRV"}]
      },
      "data":{"pageCounterRegex":"图片[,，]第\\s*(\\d+)张[,，]共\\s*(\\d+)张"},
      "flows":{
        "images":{"strategy":"horizontalCarousel","startPolicy":"rewindToFirstOrAsk","screenshotRegion":"currentImage","pageCounter":"pageIndicator","advance":{"gesture":"swipeLeft","region":"carousel"},"waitFor":"pageCounterChanged","extract":"ocr","stopWhen":"currentPageEqualsTotal"},
        "comments":{"strategy":"nestedCommentScroll","replyRelation":"ancestorOrPrecedingSiblingGroup","scrollContainer":"commentList","extractParents":"mainComment","extractChildren":"subComment","expand":"expandReplies","stopWhen":"endOfListOrStable"}
      },
      "limits":{"maxPages":50,"maxScrolls":300,"actionTimeoutMs":5000,"maxConsecutiveNoNewContent":3,"maxExpandFailures":3},
      "output":{"commentDeduplication":"parentAuthorTextTime","pageOrder":"numeric"}
    }
    """.trimIndent()
}
